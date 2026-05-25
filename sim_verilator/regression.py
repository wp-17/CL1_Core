#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

from platforms import Platform, active_platform, hex32

ROOT_DIR = Path(__file__).resolve().parent.parent
SIM_DIR = Path(__file__).resolve().parent
SELFTEST_BUILD = SIM_DIR / "selftest" / "build"
DEFAULT_TEST_MODE = os.environ.get("CL1_TEST_MODE", "bus").strip().lower() or "bus"
SIM_BIN = SIM_DIR / "build" / DEFAULT_TEST_MODE / "cl1_verilator"
CURRENT_TEST_MODE = DEFAULT_TEST_MODE
BUILD_SIM = SIM_DIR / "build.sh"
BUILD_TESTS = SIM_DIR / "build_test_programs.sh"
RUN_TESTS = SIM_DIR / "run_tests.py"
CURRENT_PLATFORM: Platform | None = None


def current_platform() -> Platform:
    if CURRENT_PLATFORM is None:
        raise RuntimeError("CL1 platform has not been initialized")
    return CURRENT_PLATFORM


def supports_color() -> bool:
    return sys.stdout.isatty()


def colorize(text: str, color: str) -> str:
    if not supports_color():
        return text
    colors = {
        "green": "\033[32m",
        "red": "\033[31m",
        "yellow": "\033[33m",
        "cyan": "\033[36m",
        "bold": "\033[1m",
    }
    return f"{colors[color]}{text}\033[0m"


@dataclass
class CheckResult:
    name: str
    ok: bool
    summary: str
    group: str = "general"


GROUP_TITLES = {
    "smoke": "Smoke",
    "core": "Core Behavior",
    "interrupt": "Interrupts",
    "harness": "Simulation Harness",
}


def output_lines(output: str) -> list[str]:
    return [line.strip() for line in output.splitlines() if line.strip()]


def summarize_output(output: str, fallback: str) -> str:
    lines = output_lines(output)
    if not lines:
        return fallback

    # Prefer the simulator's final verdict over loader or verbose trace lines.
    sim_verdicts = ("[sim] PASS", "[sim] FAIL", "[sim] TIMEOUT", "[sim] LOAD-ERROR")
    for line in reversed(lines):
        if line.startswith(sim_verdicts):
            return line

    for line in reversed(lines):
        if line.startswith("Summary:") or line.startswith("Regression summary:"):
            return line

    for line in lines:
        if line.startswith("Usage:"):
            return f"cli: {line}"

    for line in reversed(lines):
        if line.startswith("[guest]"):
            return line

    return lines[-1]


def run_command(
    name: str,
    cmd: list[str],
    group: str = "general",
    expected_rc: int = 0,
    must_contain: list[str] | None = None,
    must_not_contain: list[str] | None = None,
    artifact: Path | None = None,
) -> CheckResult:
    completed = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = completed.stdout or ""

    ok = completed.returncode == expected_rc
    if must_contain:
        ok = ok and all(token in output for token in must_contain)
    if must_not_contain:
        ok = ok and all(token not in output for token in must_not_contain)
    if artifact is not None:
        ok = ok and artifact.exists() and artifact.stat().st_size > 0

    summary = summarize_output(output, f"rc={completed.returncode}")
    if expected_rc != 0:
        summary = f"expected rc={expected_rc}: {summary}"
    if not ok:
        print(colorize(f"[regression] {name} failed", "red"), file=sys.stderr)
        print("Command:", " ".join(cmd), file=sys.stderr)
        print(output, file=sys.stderr)
    return CheckResult(name=name, ok=ok, summary=summary, group=group)


def build_env(test_mode: str) -> dict[str, str]:
    env = os.environ.copy()
    env["CL1_TEST_MODE"] = test_mode
    env["CL1_PLATFORM"] = current_platform().name
    env["CL1_ADDRESS_PROFILE"] = current_platform().name
    return env


def ensure_prereqs(skip_build_sim: bool, skip_build_tests: bool, test_mode: str) -> None:
    if not skip_build_sim:
        subprocess.run([str(BUILD_SIM)], check=True, env=build_env(test_mode))
    if not skip_build_tests:
        subprocess.run([str(BUILD_TESTS)], check=True)
    if not SIM_BIN.exists():
        raise RuntimeError(f"simulator not found: {SIM_BIN}")


def artifact(name: str, ext: str) -> Path:
    return SELFTEST_BUILD / f"{name}.{ext}"


def stage_case_dir(dst: Path, names: list[str]) -> None:
    dst.mkdir(parents=True, exist_ok=True)
    for name in names:
        for ext in ("elf", "bin", "hex"):
            src = artifact(name, ext)
            if src.exists():
                shutil.copy2(src, dst / src.name)


def harness_checks(max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    platform = current_platform()
    load_addr = hex32(platform.load_addr)
    ram_base = hex32(platform.ram_base)
    ram_size = str(platform.ram_size)
    config_region_args = platform.selftest_region_args("test_region")
    if not config_region_args:
        raise RuntimeError(f"platform {platform.name} does not define selftest region test_region")
    results.append(
        run_command(
            name="help",
            group="harness",
            cmd=[str(SIM_BIN), "--help"],
            must_contain=["Usage:"],
        )
    )
    results.append(
        run_command(
            name="bin-host-exit",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--image-type",
                "bin",
                "--load-addr",
                load_addr,
                "--ram-base",
                ram_base,
                "--ram-size",
                ram_size,
                "--max-cycles",
                str(max_cycles),
                str(artifact("host_exit_pass", "bin")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_command(
            name="hex-host-exit",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--image-type",
                "hex",
                "--load-addr",
                load_addr,
                "--max-cycles",
                str(max_cycles),
                str(artifact("host_exit_pass", "hex")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_command(
            name="bin-sidecar-elf",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--symbol-elf",
                str(artifact("tohost_pass", "elf")),
                "--max-cycles",
                str(max_cycles),
                str(artifact("tohost_pass", "bin")),
            ],
            must_contain=["PASS", "tohost write"],
        )
    )
    trace_path = SELFTEST_BUILD / "trace_smoke.fst"
    if trace_path.exists():
        trace_path.unlink()
    results.append(
        run_command(
            name="trace-output",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--trace",
                str(trace_path),
                "--max-cycles",
                str(max_cycles),
                str(artifact("host_exit_pass", "elf")),
            ],
            must_contain=["PASS"],
            artifact=trace_path,
        )
    )
    results.append(
        run_command(
            name="verbose-output",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--verbose",
                "--max-cycles",
                str(max_cycles),
                str(artifact("host_exit_pass", "bin")),
            ],
            must_contain=["[sim][cycle", "PASS"],
        )
    )
    results.append(
        run_command(
            name="quiet-output",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--quiet",
                "--max-cycles",
                str(max_cycles),
                str(artifact("host_exit_pass", "elf")),
            ],
            must_contain=["PASS"],
            must_not_contain=["loaded"],
        )
    )
    results.append(
        run_command(
            name="custom-mmio",
            group="harness",
            cmd=[
                str(SIM_BIN),
                "--host-exit-addr",
                "0x10000080",
                "--uart-addr",
                "0x10000084",
                "--max-cycles",
                str(max_cycles),
                str(artifact("custom_mmio_pass", "elf")),
            ],
            must_contain=["PASS", "CMMIO"],
        )
    )
    results.append(
        run_command(
            name="config-region",
            group="harness",
            cmd=[
                str(SIM_BIN),
                *config_region_args,
                "--max-cycles",
                str(max_cycles),
                str(artifact("config_region_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_command(
            name="fail-detection",
            group="harness",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("host_exit_fail", "elf"))],
            expected_rc=1,
            must_contain=["FAIL"],
        )
    )
    return results


def core_checks(max_cycles: int) -> list[CheckResult]:
    return [
        run_command(
            name="ebreak-exception",
            group="core",
            cmd=[
                str(SIM_BIN),
                "--no-ebreak-stop",
                "--max-cycles",
                str(max_cycles),
                str(artifact("ebreak_exception_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        ),
        run_command(
            name="illegal-instruction",
            group="core",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("illegal_instruction_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        ),
        run_command(
            name="access-fault",
            group="core",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("access_fault_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        ),
    ]


def interrupt_checks(max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    interrupt_cases = [
        ("interrupt-external", "interrupt_external_pass", "ext", "11"),
        ("interrupt-software", "interrupt_software_pass", "sft", "12"),
        ("interrupt-timer", "interrupt_timer_pass", "tmr", "13"),
    ]
    for test_name, artifact_name, irq_line, seed in interrupt_cases:
        results.append(
            run_command(
                name=test_name,
                group="interrupt",
                cmd=[
                    str(SIM_BIN),
                    "--irq-lines",
                    irq_line,
                    "--irq-seed",
                    seed,
                    "--irq-delay",
                    "1:16",
                    "--irq-width",
                    "2:4",
                    "--max-cycles",
                    str(max_cycles),
                    str(artifact(artifact_name, "elf")),
                ],
                must_contain=["PASS", "host exit register write"],
            )
        )
    return results


def smoke_checks(max_cycles: int) -> list[CheckResult]:
    return [
        run_command(
            name="illegal-instruction",
            group="smoke",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("illegal_instruction_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        )
    ]


def harness_batch_checks(max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    with tempfile.TemporaryDirectory(prefix="cl1-regression-pass-") as tmpdir:
        pass_dir = Path(tmpdir) / "harness"
        stage_case_dir(
            pass_dir,
            ["host_exit_pass", "tohost_pass", "ebreak_pass"],
        )
        for prefer in ("elf", "bin", "hex"):
            results.append(
                run_command(
                    name=f"batch-pass-{prefer}",
                    group="harness",
                    cmd=[
                        str(RUN_TESTS),
                        "--no-build",
                        "--test-mode",
                        CURRENT_TEST_MODE,
                        "--address-profile",
                        current_platform().name,
                        "--prefer-image-type",
                        prefer,
                        "--max-cycles",
                        str(max_cycles),
                        str(pass_dir),
                    ],
                    must_contain=["Summary:", "0 failed"],
                )
            )

    with tempfile.TemporaryDirectory(prefix="cl1-regression-neg-") as tmpdir:
        neg_dir = Path(tmpdir) / "negative"
        stage_case_dir(neg_dir, ["host_exit_pass", "host_exit_fail"])
        results.append(
            run_command(
                name="batch-negative",
                group="harness",
                cmd=[
                    str(RUN_TESTS),
                    "--no-build",
                    "--test-mode",
                    CURRENT_TEST_MODE,
                    "--address-profile",
                    current_platform().name,
                    "--prefer-image-type",
                    "elf",
                    "--max-cycles",
                    str(max_cycles),
                    str(neg_dir),
                ],
                expected_rc=1,
                must_contain=["Summary:", "1 failed"],
            )
        )

    return results


def report(results: list[CheckResult]) -> int:
    failed = 0
    name_width = max((len(result.name) for result in results), default=0)
    print(f"Regression results: mode={CURRENT_TEST_MODE}, platform={current_platform().name}")
    groups = list(dict.fromkeys(result.group for result in results))
    for group in groups:
        print(f"  [{GROUP_TITLES.get(group, group)}]")
        for result in (item for item in results if item.group == group):
            if result.ok:
                print(f"    {colorize('PASS', 'green')} {result.name:<{name_width}}  {result.summary}")
            else:
                failed += 1
                print(f"    {colorize('FAIL', 'red')} {result.name:<{name_width}}  {result.summary}")

    summary = f"Regression summary: {len(results) - failed} passed, {failed} failed, {len(results)} total"
    print(colorize(summary, "green" if failed == 0 else "red"))
    return 0 if failed == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Selectable regression runner for sim_verilator")
    parser.add_argument(
        "--suite",
        choices=("smoke", "core", "interrupt", "harness", "selftest", "direct", "full", "all"),
        default="smoke",
        help=(
            "test suite to run: smoke is a quick sanity check; core validates CPU-visible behavior; "
            "interrupt validates IRQ handling; selftest/full run core+interrupt; "
            "harness validates simulator CLI/load/trace/batch behavior; all runs full+harness"
        ),
    )
    parser.add_argument("--max-cycles", type=int, default=5000)
    parser.add_argument("--test-mode", choices=("bus", "cache"), default=DEFAULT_TEST_MODE)
    parser.add_argument(
        "--address-profile",
        "--platform",
        dest="platform",
        default="",
        help="CL1 platform: simple_soc or full_soc",
    )
    parser.add_argument("--no-build-sim", action="store_true")
    parser.add_argument("--no-build-tests", action="store_true")
    args = parser.parse_args()

    global SIM_BIN
    global CURRENT_TEST_MODE
    global CURRENT_PLATFORM
    CURRENT_TEST_MODE = args.test_mode
    SIM_BIN = SIM_DIR / "build" / args.test_mode / "cl1_verilator"
    CURRENT_PLATFORM = active_platform(args.platform or None)

    try:
        ensure_prereqs(args.no_build_sim, args.no_build_tests, args.test_mode)
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    results: list[CheckResult] = []
    if args.suite == "smoke":
        results.extend(smoke_checks(args.max_cycles))
    elif args.suite == "core":
        results.extend(core_checks(args.max_cycles))
    elif args.suite == "interrupt":
        results.extend(interrupt_checks(args.max_cycles))
    elif args.suite == "harness":
        results.extend(harness_checks(args.max_cycles))
        results.extend(harness_batch_checks(args.max_cycles))
    elif args.suite in ("selftest", "direct"):
        results.extend(core_checks(args.max_cycles))
        results.extend(interrupt_checks(args.max_cycles))
    elif args.suite == "full":
        results.extend(core_checks(args.max_cycles))
        results.extend(interrupt_checks(args.max_cycles))
    elif args.suite == "all":
        results.extend(core_checks(args.max_cycles))
        results.extend(interrupt_checks(args.max_cycles))
        results.extend(harness_checks(args.max_cycles))
        results.extend(harness_batch_checks(args.max_cycles))

    return report(results)


if __name__ == "__main__":
    sys.exit(main())
