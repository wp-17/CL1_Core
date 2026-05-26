#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

from platforms import active_platform

ROOT_DIR = Path(__file__).resolve().parent.parent
SIM_DIR = Path(__file__).resolve().parent
DEFAULT_MODE = os.environ.get("CL1_TEST_MODE", "bus").strip().lower() or "bus"

BUILD_SCRIPT = SIM_DIR / "build.sh"
SELFTEST_SCRIPT = SIM_DIR / "build_test_programs.sh"
RUN_TESTS = SIM_DIR / "run_tests.py"
REGRESSION = SIM_DIR / "regression.py"
RUN_RISCV_DV = SIM_DIR / "run_riscv_dv.py"


def build_env(mode: str, platform_name: str = "") -> dict[str, str]:
    platform = active_platform(platform_name or None)
    env = os.environ.copy()
    env["CL1_TEST_MODE"] = mode
    env["CL1_PLATFORM"] = platform.name
    env["CL1_ADDRESS_PROFILE"] = platform.name
    return env


def sim_bin(mode: str) -> Path:
    return SIM_DIR / "build" / mode / "cl1_verilator"


def strip_separator(args: list[str]) -> list[str]:
    if args and args[0] == "--":
        return args[1:]
    return args


def run_command(cmd: list[str], *, env: dict[str, str] | None = None, cwd: Path | None = None) -> int:
    return subprocess.run(cmd, env=env, cwd=cwd).returncode


def add_mode(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--mode",
        "--test-mode",
        choices=("bus", "cache"),
        default=DEFAULT_MODE,
        dest="mode",
        help="simulation top-level mode (default: CL1_TEST_MODE or bus)",
    )


def add_address_profile(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--address-profile",
        "--platform",
        dest="address_profile",
        default="",
        help="CL1 platform: simple_soc or full_soc",
    )


def cmd_build(args: argparse.Namespace) -> int:
    return run_command([str(BUILD_SCRIPT)], env=build_env(args.mode, args.address_profile))


def cmd_selftest(args: argparse.Namespace) -> int:
    return run_command([str(SELFTEST_SCRIPT)], env=build_env(args.mode, args.address_profile))


def cmd_run(args: argparse.Namespace) -> int:
    env = build_env(args.mode, args.address_profile)
    if not args.no_build:
        rc = run_command([str(BUILD_SCRIPT)], env=env)
        if rc != 0:
            return rc

    binary = sim_bin(args.mode)
    if not binary.exists():
        print(f"error: simulator not found: {binary}", file=sys.stderr)
        return 1

    user_sim_args = strip_separator(args.sim_args)
    if not user_sim_args:
        print("error: run requires simulator arguments ending with an image path", file=sys.stderr)
        print("example: cl1_sim.py run -- --max-cycles 5000 path/to/test.elf", file=sys.stderr)
        return 2
    platform = active_platform(args.address_profile or None)
    sim_args = platform.sim_args() + user_sim_args
    return run_command([str(binary), *sim_args])


def cmd_test(args: argparse.Namespace) -> int:
    cmd = [
        str(RUN_TESTS),
        "--test-mode",
        args.mode,
        "--max-cycles",
        str(args.max_cycles),
        "--prefer-image-type",
        args.prefer_image_type,
    ]
    if args.no_build:
        cmd.append("--no-build")
    if args.address_profile:
        cmd.extend(["--address-profile", args.address_profile])
    if args.load_addr:
        cmd.extend(["--load-addr", args.load_addr])
    if args.filter:
        cmd.extend(["--filter", args.filter])
    for sim_arg in args.sim_arg:
        cmd.extend(["--sim-arg", sim_arg])
    cmd.append(args.tests_dir)
    return run_command(cmd)


def cmd_regression(args: argparse.Namespace) -> int:
    cmd = [
        str(REGRESSION),
        "--test-mode",
        args.mode,
        "--suite",
        args.suite,
        "--max-cycles",
        str(args.max_cycles),
    ]
    if args.no_build_sim:
        cmd.append("--no-build-sim")
    if args.no_build_tests:
        cmd.append("--no-build-tests")
    if args.address_profile:
        cmd.extend(["--address-profile", args.address_profile])
    return run_command(cmd)


def cmd_riscv_dv(args: argparse.Namespace) -> int:
    cmd = [
        str(RUN_RISCV_DV),
        "--test-mode",
        args.mode,
        "--max-cycles",
        str(args.max_cycles),
    ]
    if args.no_build:
        cmd.append("--no-build")
    if args.compare:
        cmd.append("--compare")
    if args.filter:
        cmd.extend(["--filter", args.filter])
    if args.address_profile:
        cmd.extend(["--address-profile", args.address_profile])
    if args.riscv_dv_root:
        cmd.extend(["--riscv-dv-root", args.riscv_dv_root])
    if args.work_dir:
        cmd.extend(["--work-dir", args.work_dir])
    for sim_arg in args.sim_arg:
        cmd.extend(["--sim-arg", sim_arg])
    if args.suite:
        cmd.append(args.suite)
    return run_command(cmd, cwd=ROOT_DIR)


def cmd_check(args: argparse.Namespace) -> int:
    regression_suites = {
        "quick": ["smoke"],
        "selftest": ["selftest"],
        "full": ["full"],
        "harness": ["harness"],
        "all": ["all"],
    }[args.level]

    for suite in regression_suites:
        cmd = [
            str(REGRESSION),
            "--test-mode",
            args.mode,
            "--suite",
            suite,
            "--max-cycles",
            str(args.max_cycles),
        ]
        if args.no_build_sim:
            cmd.append("--no-build-sim")
        if args.no_build_tests:
            cmd.append("--no-build-tests")
        if args.address_profile:
            cmd.extend(["--address-profile", args.address_profile])
        rc = run_command(cmd)
        if rc != 0:
            return rc

    if args.level != "all" or args.no_riscv_dv:
        return 0

    cmd = [
        str(RUN_RISCV_DV),
        "--test-mode",
        args.mode,
        "--no-build",
        "--compare",
        "--max-cycles",
        str(args.riscv_dv_max_cycles),
    ]
    if args.address_profile:
        cmd.extend(["--address-profile", args.address_profile])
    if args.riscv_dv_root:
        cmd.extend(["--riscv-dv-root", args.riscv_dv_root])
    if args.riscv_dv_suite:
        cmd.append(args.riscv_dv_suite)
    return run_command(cmd, cwd=ROOT_DIR)


def cmd_clean(_: argparse.Namespace) -> int:
    for path in (SIM_DIR / "build", SIM_DIR / "selftest" / "build"):
        if path.exists():
            shutil.rmtree(path)
            print(f"removed {path.relative_to(ROOT_DIR)}")
    return 0


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Unified CL1 Verilator simulation command",
    )
    subparsers = parser.add_subparsers(dest="command", metavar="<command>")

    build = subparsers.add_parser("build", help="build the Verilator simulator")
    add_mode(build)
    add_address_profile(build)
    build.set_defaults(func=cmd_build)

    selftest = subparsers.add_parser("selftest", help="build built-in bare-metal selftests")
    add_mode(selftest)
    add_address_profile(selftest)
    selftest.set_defaults(func=cmd_selftest)

    run = subparsers.add_parser("run", help="run one ELF/BIN/HEX image")
    add_mode(run)
    add_address_profile(run)
    run.add_argument("--no-build", action="store_true", help="skip simulator build")
    run.add_argument(
        "sim_args",
        nargs=argparse.REMAINDER,
        help="arguments passed to cl1_verilator, optionally prefixed by --",
    )
    run.set_defaults(func=cmd_run)

    test = subparsers.add_parser("test", help="run a directory of ELF/BIN/HEX tests")
    add_mode(test)
    add_address_profile(test)
    test.add_argument("--no-build", action="store_true", help="skip simulator build")
    test.add_argument("--max-cycles", type=int, default=1_000_000)
    test.add_argument("--load-addr", default="")
    test.add_argument("--prefer-image-type", choices=("elf", "bin", "hex"), default="elf")
    test.add_argument("--filter", default="")
    test.add_argument("--sim-arg", action="append", default=[])
    test.add_argument("tests_dir", nargs="?", default="tests")
    test.set_defaults(func=cmd_test)

    regression = subparsers.add_parser("regression", help="run grouped built-in regression suites")
    add_mode(regression)
    add_address_profile(regression)
    regression.add_argument(
        "--suite",
        choices=("smoke", "core", "interrupt", "harness", "selftest", "direct", "full", "all"),
        default="smoke",
    )
    regression.add_argument("--max-cycles", type=int, default=5000)
    regression.add_argument("--no-build-sim", action="store_true")
    regression.add_argument("--no-build-tests", action="store_true")
    regression.set_defaults(func=cmd_regression)

    check = subparsers.add_parser("check", help="run the unified project validation flow")
    add_mode(check)
    add_address_profile(check)
    check.add_argument(
        "--level",
        choices=("quick", "selftest", "full", "harness", "all"),
        default="quick",
        help=(
            "quick=smoke, selftest/full=processor core tests, "
            "harness=simulator framework tests, all=core+harness+riscv-dv compare"
        ),
    )
    check.add_argument("--max-cycles", type=int, default=20000)
    check.add_argument("--no-build-sim", action="store_true")
    check.add_argument("--no-build-tests", action="store_true")
    check.add_argument("--no-riscv-dv", action="store_true", help="skip riscv-dv when --level all is selected")
    check.add_argument("--riscv-dv-root", default="")
    check.add_argument("--riscv-dv-suite", default="")
    check.add_argument("--riscv-dv-max-cycles", type=int, default=200000)
    check.set_defaults(func=cmd_check)

    riscv_dv = subparsers.add_parser("riscv-dv", help="run riscv-dv generated cases")
    add_mode(riscv_dv)
    add_address_profile(riscv_dv)
    riscv_dv.add_argument("--no-build", action="store_true", help="skip simulator build")
    riscv_dv.add_argument("--compare", action="store_true")
    riscv_dv.add_argument("--max-cycles", type=int, default=1_000_000)
    riscv_dv.add_argument("--filter", default="")
    riscv_dv.add_argument("--riscv-dv-root", default="")
    riscv_dv.add_argument("--work-dir", default="")
    riscv_dv.add_argument("--sim-arg", action="append", default=[])
    riscv_dv.add_argument("suite", nargs="?", default="")
    riscv_dv.set_defaults(func=cmd_riscv_dv)

    clean = subparsers.add_parser("clean", help="remove simulator and selftest build outputs")
    clean.set_defaults(func=cmd_clean)

    return parser


def main() -> int:
    parser = make_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return 2
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
