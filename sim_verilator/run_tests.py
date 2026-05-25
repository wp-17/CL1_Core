#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from platforms import active_platform

SIM_DIR = Path(__file__).resolve().parent
DEFAULT_TEST_MODE = os.environ.get("CL1_TEST_MODE", "bus").strip().lower() or "bus"
DEFAULT_SIM = SIM_DIR / "build" / DEFAULT_TEST_MODE / "cl1_verilator"
DEFAULT_BUILD = SIM_DIR / "build.sh"
SUPPORTED_EXTS = (".elf", ".bin", ".hex")
EXT_BY_NAME = {"elf": ".elf", "bin": ".bin", "hex": ".hex"}


def preference_map(prefer_image_type: str) -> dict[str, int]:
    ordered = [EXT_BY_NAME[prefer_image_type]]
    ordered.extend(ext for ext in SUPPORTED_EXTS if ext not in ordered)
    return {ext: index for index, ext in enumerate(ordered)}


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


@dataclass(frozen=True)
class TestCase:
    name: str
    image: Path
    symbol_elf: Path | None


def discover_tests(root: Path, prefer_image_type: str) -> list[TestCase]:
    preference = preference_map(prefer_image_type)
    groups: dict[str, list[Path]] = {}
    for ext in SUPPORTED_EXTS:
        for path in root.rglob(f"*{ext}"):
            if not path.is_file():
                continue
            key = str(path.relative_to(root).with_suffix(""))
            groups.setdefault(key, []).append(path)

    cases: list[TestCase] = []
    for key in sorted(groups):
        paths = sorted(groups[key], key=lambda path: preference[path.suffix.lower()])
        chosen = paths[0]
        symbol_elf = next((path for path in paths if path.suffix.lower() == ".elf"), None)
        cases.append(TestCase(name=key, image=chosen, symbol_elf=symbol_elf))
    return cases


def ensure_built(sim_bin: Path, build_script: Path, test_mode: str, platform_name: str) -> None:
    env = os.environ.copy()
    env["CL1_TEST_MODE"] = test_mode
    env["CL1_PLATFORM"] = platform_name
    env["CL1_ADDRESS_PROFILE"] = platform_name
    subprocess.run([str(build_script)], check=True, env=env)
    if not sim_bin.exists():
        raise RuntimeError(f"simulator was not produced: {sim_bin}")


def one_line_summary(output: str) -> str:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        return "(no simulator output)"

    sim_verdicts = ("[sim] PASS", "[sim] FAIL", "[sim] TIMEOUT", "[sim] LOAD-ERROR")
    for line in reversed(lines):
        if line.startswith(sim_verdicts):
            return line

    for line in reversed(lines):
        if line.startswith("[guest]"):
            return line

    return lines[-1]


def run_case(
    case: TestCase,
    sim_bin: Path,
    logs_dir: Path,
    max_cycles: int,
    extra_sim_args: Iterable[str],
) -> tuple[bool, float, str, Path]:
    cmd = [str(sim_bin), "--max-cycles", str(max_cycles)]
    if case.symbol_elf is not None and case.symbol_elf != case.image:
        cmd.extend(["--symbol-elf", str(case.symbol_elf)])
    cmd.extend(extra_sim_args)
    cmd.append(str(case.image))

    start = time.perf_counter()
    completed = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    elapsed = time.perf_counter() - start

    log_path = logs_dir / f"{case.name.replace('/', '__')}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    output = completed.stdout or ""
    log_path.write_text(output)

    summary = one_line_summary(output)
    return completed.returncode == 0, elapsed, summary, log_path


def main() -> int:
    try:
        parser = argparse.ArgumentParser(description="Batch Verilator regression runner for Cl1Top")
        parser.add_argument("tests_dir", nargs="?", default="tests", help="directory containing .elf/.bin/.hex tests")
        parser.add_argument("--sim", default=str(DEFAULT_SIM), help="path to the compiled Verilator simulator")
        parser.add_argument("--build-script", default=str(DEFAULT_BUILD), help="path to the simulator build script")
        parser.add_argument("--test-mode", choices=("bus", "cache"), default=DEFAULT_TEST_MODE)
        parser.add_argument("--no-build", action="store_true", help="skip the build step")
        parser.add_argument("--max-cycles", type=int, default=1_000_000, help="timeout passed to the simulator")
        parser.add_argument(
            "--address-profile",
            "--platform",
            dest="platform",
            default="",
            help="CL1 platform: simple_soc or full_soc",
        )
        parser.add_argument("--load-addr", default="", help="base address for BIN/HEX images")
        parser.add_argument(
            "--prefer-image-type",
            choices=("elf", "bin", "hex"),
            default="elf",
            help="preferred image type when multiple formats share the same test stem",
        )
        parser.add_argument("--filter", default="", help="only run tests whose grouped name contains this substring")
        parser.add_argument(
            "--sim-arg",
            action="append",
            default=[],
            help="extra argument passed through to the simulator, can be repeated",
        )
        args = parser.parse_args()
        platform = active_platform(args.platform or None)
        extra_sim_args = platform.sim_args(load_addr=args.load_addr or None) + args.sim_arg

        tests_root = Path(args.tests_dir).resolve()
        sim_bin = Path(args.sim).resolve()
        if args.sim == str(DEFAULT_SIM):
            sim_bin = (SIM_DIR / "build" / args.test_mode / "cl1_verilator").resolve()
        build_script = Path(args.build_script).resolve()
        logs_dir = SIM_DIR / "build" / args.test_mode / "test_logs"

        if not args.no_build:
            ensure_built(sim_bin, build_script, args.test_mode, platform.name)

        if not sim_bin.exists():
            print(f"error: simulator not found: {sim_bin}", file=sys.stderr)
            return 1

        if not tests_root.exists():
            print(f"error: tests directory does not exist: {tests_root}", file=sys.stderr)
            return 1

        cases = discover_tests(tests_root, args.prefer_image_type)
        if args.filter:
            cases = [case for case in cases if args.filter in case.name]

        if not cases:
            print(f"error: no .elf/.bin/.hex tests found under {tests_root}", file=sys.stderr)
            return 1

        print(colorize(f"Running {len(cases)} test(s) with {sim_bin} (prefer {args.prefer_image_type})", "cyan"))
        print(f"  platform: {platform.name}")

        passed = 0
        failed = 0
        for index, case in enumerate(cases, start=1):
            ok, elapsed, summary, log_path = run_case(
                case=case,
                sim_bin=sim_bin,
                logs_dir=logs_dir,
                max_cycles=args.max_cycles,
                extra_sim_args=extra_sim_args,
            )

            status = colorize("PASS", "green") if ok else colorize("FAIL", "red")
            print(f"[{index:>3}/{len(cases):>3}] {status} {case.name} ({elapsed:.2f}s)  {summary}")
            if ok:
                passed += 1
            else:
                failed += 1
                print(f"      log: {log_path}")

        total = passed + failed
        summary = f"Summary: {passed} passed, {failed} failed, {total} total"
        if failed == 0:
            print(colorize(summary, "green"))
            return 0

        print(colorize(summary, "red"))
        return 1
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
