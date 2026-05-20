#!/usr/bin/env python3

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
SIM_DIR = Path(__file__).resolve().parent
SELFTEST_BUILD = SIM_DIR / "selftest" / "build"
SIM_BIN = SIM_DIR / "build" / "cl1_verilator"
BUILD_SIM = SIM_DIR / "build.sh"
BUILD_TESTS = SIM_DIR / "build_test_programs.sh"
RUN_TESTS = SIM_DIR / "run_tests.py"


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


def run_command(
    name: str,
    cmd: list[str],
    expected_rc: int = 0,
    must_contain: list[str] | None = None,
    must_not_contain: list[str] | None = None,
    artifact: Path | None = None,
) -> CheckResult:
    completed = subprocess.run(cmd, capture_output=True, text=True)
    output = completed.stdout + completed.stderr

    ok = completed.returncode == expected_rc
    if must_contain:
        ok = ok and all(token in output for token in must_contain)
    if must_not_contain:
        ok = ok and all(token not in output for token in must_not_contain)
    if artifact is not None:
        ok = ok and artifact.exists() and artifact.stat().st_size > 0

    summary = output.strip().splitlines()[-1] if output.strip() else f"rc={completed.returncode}"
    if not ok:
        print(colorize(f"[regression] {name} failed", "red"), file=sys.stderr)
        print("Command:", " ".join(cmd), file=sys.stderr)
        print(output, file=sys.stderr)
    return CheckResult(name=name, ok=ok, summary=summary)


def ensure_prereqs(skip_build_sim: bool, skip_build_tests: bool) -> None:
    if not skip_build_sim:
        subprocess.run([str(BUILD_SIM)], check=True)
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


def direct_checks(max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    results.append(
        run_command(
            name="help",
            cmd=[str(SIM_BIN), "--help"],
            must_contain=["Usage:"],
        )
    )
    results.append(
        run_command(
            name="elf-tohost",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("tohost_pass", "elf"))],
            must_contain=["PASS", "tohost write"],
        )
    )
    results.append(
        run_command(
            name="bin-host-exit",
            cmd=[
                str(SIM_BIN),
                "--image-type",
                "bin",
                "--load-addr",
                "0x80000000",
                "--ram-base",
                "0x80000000",
                "--ram-size",
                "64K",
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
            cmd=[
                str(SIM_BIN),
                "--image-type",
                "hex",
                "--load-addr",
                "0x80000000",
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
            name="ebreak",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("ebreak_pass", "elf"))],
            must_contain=["PASS", "ebreak"],
        )
    )
    results.append(
        run_command(
            name="illegal-instruction",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("illegal_instruction_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_command(
            name="access-fault",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("access_fault_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_command(
            name="config-region",
            cmd=[
                str(SIM_BIN),
                "--region",
                "test_region:0x60000000:4:rw",
                "--max-cycles",
                str(max_cycles),
                str(artifact("config_region_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    interrupt_cases = [
        ("interrupt-external", "interrupt_external_pass", "ext", "11"),
        ("interrupt-software", "interrupt_software_pass", "sft", "12"),
        ("interrupt-timer", "interrupt_timer_pass", "tmr", "13"),
    ]
    for test_name, artifact_name, irq_line, seed in interrupt_cases:
        results.append(
            run_command(
                name=test_name,
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
    results.append(
        run_command(
            name="fail-detection",
            cmd=[str(SIM_BIN), "--max-cycles", str(max_cycles), str(artifact("host_exit_fail", "elf"))],
            expected_rc=1,
            must_contain=["FAIL"],
        )
    )
    return results


def batch_checks(max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    with tempfile.TemporaryDirectory(prefix="cl1-regression-pass-") as tmpdir:
        pass_dir = Path(tmpdir) / "pass"
        stage_case_dir(
            pass_dir,
            ["host_exit_pass", "tohost_pass", "ebreak_pass", "illegal_instruction_pass", "access_fault_pass"],
        )
        for prefer in ("elf", "bin", "hex"):
            results.append(
                run_command(
                    name=f"batch-pass-{prefer}",
                    cmd=[
                        str(RUN_TESTS),
                        "--no-build",
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
                cmd=[
                    str(RUN_TESTS),
                    "--no-build",
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
    for result in results:
        if result.ok:
            print(f"{colorize('PASS', 'green')} {result.name}: {result.summary}")
        else:
            failed += 1
            print(f"{colorize('FAIL', 'red')} {result.name}: {result.summary}")

    summary = f"Regression summary: {len(results) - failed} passed, {failed} failed, {len(results)} total"
    print(colorize(summary, "green" if failed == 0 else "red"))
    return 0 if failed == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Selectable regression runner for sim_verilator")
    parser.add_argument("--suite", choices=("smoke", "direct", "batch", "full"), default="smoke")
    parser.add_argument("--max-cycles", type=int, default=5000)
    parser.add_argument("--no-build-sim", action="store_true")
    parser.add_argument("--no-build-tests", action="store_true")
    args = parser.parse_args()

    try:
        ensure_prereqs(args.no_build_sim, args.no_build_tests)
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    results: list[CheckResult] = []
    if args.suite == "smoke":
        results.extend(direct_checks(args.max_cycles)[:3])
    elif args.suite == "direct":
        results.extend(direct_checks(args.max_cycles))
    elif args.suite == "batch":
        results.extend(batch_checks(args.max_cycles))
    elif args.suite == "full":
        results.extend(direct_checks(args.max_cycles))
        results.extend(batch_checks(args.max_cycles))

    return report(results)


if __name__ == "__main__":
    sys.exit(main())
