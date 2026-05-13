#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
SIM_DIR = Path(__file__).resolve().parent
DEFAULT_SIM = SIM_DIR / "build" / "cl1_verilator"
DEFAULT_BUILD = SIM_DIR / "build.sh"
DEFAULT_RISCV_DV_ROOT = ROOT_DIR.parent / "riscv-dv"
DEFAULT_SUITE = DEFAULT_RISCV_DV_ROOT / "verification_output" / "rv32imc_mmode_directed_suite"
RTL_TO_CSV = SIM_DIR / "rtl_commit_log_to_trace_csv.py"
SPIKE_TO_CSV = DEFAULT_RISCV_DV_ROOT / "scripts" / "spike_log_to_trace_csv.py"
TRACE_COMPARE = DEFAULT_RISCV_DV_ROOT / "scripts" / "instr_trace_compare.py"
COMPARE_SUMMARY_RE = re.compile(
    r"^\[(?P<status>PASSED|FAILED)\]: (?P<matched>\d+) matched(?:, (?P<mismatch>\d+) mismatch)?$",
    re.MULTILINE,
)


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
class Case:
    name: str
    image: Path
    symbol_elf: Path | None
    spike_log: Path | None


@dataclass
class CaseResult:
    case: Case
    run_ok: bool
    run_elapsed: float
    run_summary: str
    run_log: Path
    compare_status: str
    compare_summary: str
    compare_log: Path | None


def choose_image(paths: list[Path]) -> Path:
    for suffix in (".bin", ".elf", ".o"):
        match = next((path for path in paths if path.suffix.lower() == suffix), None)
        if match is not None:
            return match
    raise RuntimeError("no runnable image found")


def choose_symbol_elf(paths: list[Path], image: Path) -> Path | None:
    if image.suffix.lower() in (".elf", ".o"):
        return image
    for suffix in (".elf", ".o"):
        match = next((path for path in paths if path.suffix.lower() == suffix), None)
        if match is not None:
            return match
    return None


def discover_flat_cases(suite_root: Path) -> list[Case]:
    search_root = suite_root
    for candidate in ("testcases", "asm_test", "directed_asm_test"):
        candidate_path = suite_root / candidate
        if candidate_path.is_dir():
            search_root = candidate_path
            break

    groups: dict[str, list[Path]] = {}
    for suffix in (".bin", ".elf", ".o"):
        for path in search_root.rglob(f"*{suffix}"):
            if not path.is_file():
                continue
            key = str(path.relative_to(search_root).with_suffix(""))
            groups.setdefault(key, []).append(path)

    cases: list[Case] = []
    for key in sorted(groups):
        paths = sorted(groups[key])
        image = choose_image(paths)
        symbol_elf = choose_symbol_elf(paths, image)
        base_name = Path(key).name
        spike_candidates = [
            image.parent / "spike.log",
            suite_root / "spike_sim" / f"{base_name}.log",
            suite_root / f"{base_name}.log",
        ]
        spike_log = next((path for path in spike_candidates if path.is_file()), None)
        cases.append(Case(name=key, image=image, symbol_elf=symbol_elf, spike_log=spike_log))
    return cases


def discover_separated_cases(suite_root: Path) -> list[Case]:
    cases_root = suite_root / "testcases"
    cases: list[Case] = []
    for case_dir in sorted(path for path in cases_root.iterdir() if path.is_dir()):
        artifacts = sorted(
            path for path in case_dir.iterdir() if path.is_file() and path.suffix.lower() in (".bin", ".elf", ".o")
        )
        if not artifacts:
            continue
        image = choose_image(artifacts)
        symbol_elf = choose_symbol_elf(artifacts, image)
        spike_log = case_dir / "spike.log"
        cases.append(
            Case(
                name=case_dir.name,
                image=image,
                symbol_elf=symbol_elf,
                spike_log=spike_log if spike_log.is_file() else None,
            )
        )
    return cases


def discover_cases(suite_root: Path) -> list[Case]:
    if (suite_root / "testcases").is_dir():
        cases = discover_separated_cases(suite_root)
        if cases:
            return cases
    return discover_flat_cases(suite_root)


def ensure_built(sim_bin: Path, build_script: Path) -> None:
    subprocess.run([str(build_script)], check=True)
    if not sim_bin.exists():
        raise RuntimeError(f"simulator was not produced: {sim_bin}")


def one_line_summary(output: str) -> str:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    return lines[-1] if lines else "(no simulator output)"


def sanitize_case_name(name: str) -> str:
    return name.replace("/", "__")


@dataclass(frozen=True)
class TraceEntry:
    row_index: int
    pc: str
    binary: str
    instr_str: str
    gpr: list[str]
    csr: list[str]
    mode: str


@dataclass(frozen=True)
class CompareSummary:
    status: str
    matched: int
    mismatches: int
    text: str


@dataclass(frozen=True)
class CompareSample:
    sample_index: int
    note: str
    spike: TraceEntry | None
    rtl: TraceEntry | None


def split_trace_field(text: str) -> list[str]:
    return [item for item in text.split(";") if item] if text else []


def normalize_instr_text(text: str) -> str:
    return " ".join(text.split())


def load_trace_csv(path: Path) -> list[TraceEntry]:
    entries: list[TraceEntry] = []
    with path.open("r", encoding="utf-8", newline="") as csv_file:
        reader = csv.DictReader(csv_file)
        for row_index, row in enumerate(reader, start=1):
            entries.append(
                TraceEntry(
                    row_index=row_index,
                    pc=row["pc"],
                    binary=row["binary"],
                    instr_str=normalize_instr_text(row["instr_str"]),
                    gpr=split_trace_field(row["gpr"]),
                    csr=split_trace_field(row["csr"]),
                    mode=row["mode"],
                )
            )
    return entries


def enrich_trace_entries(entries: list[TraceEntry], reference_entries: list[TraceEntry]) -> list[TraceEntry]:
    by_pc_and_binary: dict[tuple[str, str], str] = {}
    by_binary: dict[str, set[str]] = {}
    for entry in reference_entries:
        if not entry.instr_str:
            continue
        by_pc_and_binary.setdefault((entry.pc, entry.binary), entry.instr_str)
        by_binary.setdefault(entry.binary, set()).add(entry.instr_str)

    unique_by_binary = {binary: next(iter(instrs)) for binary, instrs in by_binary.items() if len(instrs) == 1}

    enriched: list[TraceEntry] = []
    for entry in entries:
        instr_str = entry.instr_str
        if not instr_str:
            instr_str = by_pc_and_binary.get((entry.pc, entry.binary), unique_by_binary.get(entry.binary, ""))
        enriched.append(
            TraceEntry(
                row_index=entry.row_index,
                pc=entry.pc,
                binary=entry.binary,
                instr_str=instr_str,
                gpr=entry.gpr,
                csr=entry.csr,
                mode=entry.mode,
            )
        )
    return enriched


def apply_gpr_update(entry: TraceEntry, gpr_state: dict[str, str]) -> bool:
    if not entry.gpr:
        return False

    state_changed = False
    for update in entry.gpr:
        reg, value = update.split(":", 1)
        prev_value = gpr_state.get(reg)
        if prev_value is None:
            if int(value, 16) != 0:
                state_changed = True
        elif prev_value != value:
            state_changed = True
        gpr_state[reg] = value
    return state_changed


def count_state_changes(entries: list[TraceEntry]) -> int:
    gpr_state: dict[str, str] = {}
    count = 0
    for entry in entries:
        if apply_gpr_update(entry, gpr_state):
            count += 1
    return count


def classify_sample(spike: TraceEntry | None, rtl: TraceEntry | None) -> str:
    if spike is not None and rtl is None:
        return "Spike still has architectural updates after RTL trace stopped matching."
    if spike is None and rtl is not None:
        return "RTL still has architectural updates after Spike trace stopped matching."
    if spike is None or rtl is None:
        return "Trace mismatch."
    if spike.pc != rtl.pc:
        return "PC diverged before the next architectural state update."
    if spike.binary != rtl.binary:
        return "Same compare slot, but the committed instruction binary differs."
    if spike.gpr != rtl.gpr:
        return "Same instruction slot, but the committed register update differs."
    return "Trace mismatch."


def collect_compare_samples(
    spike_entries: list[TraceEntry],
    rtl_entries: list[TraceEntry],
    limit: int = 8,
) -> list[CompareSample]:
    samples: list[CompareSample] = []
    spike_state: dict[str, str] = {}
    rtl_state: dict[str, str] = {}
    rtl_cursor = 0

    for spike_entry in spike_entries:
        if not apply_gpr_update(spike_entry, spike_state):
            continue

        rtl_entry: TraceEntry | None = None
        while rtl_cursor < len(rtl_entries):
            candidate = rtl_entries[rtl_cursor]
            rtl_cursor += 1
            if apply_gpr_update(candidate, rtl_state):
                rtl_entry = candidate
                break

        if rtl_entry is None:
            samples.append(
                CompareSample(
                    sample_index=len(samples) + 1,
                    note=classify_sample(spike_entry, None),
                    spike=spike_entry,
                    rtl=None,
                )
            )
            return samples

        if spike_entry.gpr != rtl_entry.gpr:
            samples.append(
                CompareSample(
                    sample_index=len(samples) + 1,
                    note=classify_sample(spike_entry, rtl_entry),
                    spike=spike_entry,
                    rtl=rtl_entry,
                )
            )
            if len(samples) >= limit:
                return samples

    while rtl_cursor < len(rtl_entries) and len(samples) < limit:
        candidate = rtl_entries[rtl_cursor]
        rtl_cursor += 1
        if apply_gpr_update(candidate, rtl_state):
            samples.append(
                CompareSample(
                    sample_index=len(samples) + 1,
                    note=classify_sample(None, candidate),
                    spike=None,
                    rtl=candidate,
                )
            )
    return samples


def parse_compare_summary(compare_text: str) -> CompareSummary | None:
    matches = list(COMPARE_SUMMARY_RE.finditer(compare_text))
    if not matches:
        return None
    match = matches[-1]
    return CompareSummary(
        status=match.group("status"),
        matched=int(match.group("matched")),
        mismatches=int(match.group("mismatch") or 0),
        text=match.group(0),
    )


def format_trace_entry(entry: TraceEntry) -> str:
    parts = [f"pc=0x{entry.pc}", f"bin=0x{entry.binary}"]
    if entry.instr_str:
        parts.append(f'instr="{entry.instr_str}"')
    if entry.gpr:
        parts.append(f"gpr={', '.join(entry.gpr)}")
    if entry.csr:
        parts.append(f"csr={', '.join(entry.csr)}")
    return " ".join(parts)


def build_compare_report(
    case: Case,
    spike_csv: Path,
    rtl_csv: Path,
    raw_compare_log: Path,
) -> str:
    raw_compare_text = raw_compare_log.read_text(encoding="utf-8").strip()
    summary = parse_compare_summary(raw_compare_text)

    spike_entries = load_trace_csv(spike_csv)
    rtl_entries = enrich_trace_entries(load_trace_csv(rtl_csv), spike_entries)
    compare_samples = collect_compare_samples(spike_entries, rtl_entries)

    report: list[str] = []
    report.append(f"Compare Report: {case.name}")
    report.append("")
    report.append("Summary")
    if summary is not None:
        report.append(f"  status: {summary.status}")
        report.append(f"  matched architectural updates: {summary.matched}")
        report.append(f"  mismatched architectural updates: {summary.mismatches}")
    else:
        report.append("  status: UNKNOWN")
    report.append(f"  spike trace rows: {len(spike_entries)}")
    report.append(f"  rtl trace rows: {len(rtl_entries)}")
    report.append(f"  spike state updates: {count_state_changes(spike_entries)}")
    report.append(f"  rtl state updates: {count_state_changes(rtl_entries)}")
    report.append("")
    report.append("Artifacts")
    report.append(f"  spike csv: {spike_csv}")
    report.append(f"  rtl csv: {rtl_csv}")
    report.append(f"  raw compare log: {raw_compare_log}")

    if compare_samples:
        report.append("")
        report.append("Sample Mismatches")
        for sample in compare_samples:
            report.append(f"  {sample.sample_index}. {sample.note}")
            if sample.spike is not None:
                report.append(f"     spike row {sample.spike.row_index}: {format_trace_entry(sample.spike)}")
            if sample.rtl is not None:
                report.append(f"     rtl row {sample.rtl.row_index}: {format_trace_entry(sample.rtl)}")

    report.append("")
    report.append("Raw Compare Output")
    report.append(raw_compare_text or "(empty)")
    return "\n".join(report) + "\n"


def compare_case(
    case: Case,
    case_dir: Path,
    riscv_dv_root: Path,
) -> tuple[str, str, Path | None]:
    if case.spike_log is None:
        return "skip", "no spike.log found", None

    spike_to_csv = riscv_dv_root / "scripts" / "spike_log_to_trace_csv.py"
    trace_compare = riscv_dv_root / "scripts" / "instr_trace_compare.py"
    if not spike_to_csv.exists() or not trace_compare.exists():
        return "error", f"missing riscv-dv compare scripts under {riscv_dv_root}", None

    rtl_commit = case_dir / "rtl_commit.log"
    rtl_csv = case_dir / "rtl.csv"
    spike_csv = case_dir / "spike.csv"
    compare_log = case_dir / "compare.log"
    raw_compare_log = case_dir / "compare.raw.log"

    subprocess.run(
        [sys.executable, str(RTL_TO_CSV), "--log", str(rtl_commit), "--csv", str(rtl_csv)],
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        [sys.executable, str(spike_to_csv), "--log", str(case.spike_log), "--csv", str(spike_csv)],
        check=True,
        capture_output=True,
        text=True,
    )
    if raw_compare_log.exists():
        raw_compare_log.unlink()
    subprocess.run(
        [
            sys.executable,
            str(trace_compare),
            "--csv_file_1",
            str(spike_csv),
            "--csv_file_2",
            str(rtl_csv),
            "--csv_name_1",
            "spike",
            "--csv_name_2",
            "rtl",
            "--mismatch_print_limit",
            "20",
            "--log",
            str(raw_compare_log),
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    compare_text = raw_compare_log.read_text(encoding="utf-8")
    compare_log.write_text(build_compare_report(case, spike_csv, rtl_csv, raw_compare_log), encoding="utf-8")
    summary = parse_compare_summary(compare_text)
    if summary is None:
        return "error", "compare completed without a final status marker", compare_log
    if summary.status == "PASSED":
        return "pass", summary.text, compare_log
    if summary.status == "FAILED":
        return "fail", summary.text, compare_log
    if "[PASSED]" in compare_text:
        return "pass", summary, compare_log
    if "[FAILED]" in compare_text:
        return "fail", summary, compare_log
    return "error", "compare completed without a final status marker", compare_log


def run_case(
    case: Case,
    sim_bin: Path,
    work_dir: Path,
    max_cycles: int,
    extra_sim_args: list[str],
    compare: bool,
    riscv_dv_root: Path,
) -> CaseResult:
    case_dir = work_dir / sanitize_case_name(case.name)
    case_dir.mkdir(parents=True, exist_ok=True)
    run_log = case_dir / "simulator.log"

    cmd = [str(sim_bin), "--max-cycles", str(max_cycles)]
    if case.image.suffix.lower() == ".o":
        cmd.extend(["--image-type", "elf"])
    if compare:
        cmd.extend(["--commit-log", str(case_dir / "rtl_commit.log")])
    if case.symbol_elf is not None and case.symbol_elf != case.image:
        cmd.extend(["--symbol-elf", str(case.symbol_elf)])
    cmd.extend(extra_sim_args)
    cmd.append(str(case.image))

    start = time.perf_counter()
    completed = subprocess.run(cmd, capture_output=True, text=True)
    elapsed = time.perf_counter() - start

    output = completed.stdout + completed.stderr
    run_log.write_text(output, encoding="utf-8")

    compare_status = "skip"
    compare_summary = "compare disabled"
    compare_log = None
    if completed.returncode == 0 and compare:
        try:
            compare_status, compare_summary, compare_log = compare_case(
                case=case,
                case_dir=case_dir,
                riscv_dv_root=riscv_dv_root,
            )
        except (RuntimeError, subprocess.CalledProcessError) as exc:
            compare_status = "error"
            compare_summary = str(exc)

    return CaseResult(
        case=case,
        run_ok=completed.returncode == 0,
        run_elapsed=elapsed,
        run_summary=one_line_summary(output),
        run_log=run_log,
        compare_status=compare_status,
        compare_summary=compare_summary,
        compare_log=compare_log,
    )


def main() -> int:
    try:
        parser = argparse.ArgumentParser(description="Run riscv-dv-generated cases on the CL1 Verilator model")
        parser.add_argument(
            "suite",
            nargs="?",
            default=str(DEFAULT_SUITE),
            help="riscv-dv output directory, for example ../riscv-dv/verification_output/rv32imc_mmode_directed_suite",
        )
        parser.add_argument("--sim", default=str(DEFAULT_SIM), help="path to the compiled Verilator simulator")
        parser.add_argument("--build-script", default=str(DEFAULT_BUILD), help="path to the simulator build script")
        parser.add_argument("--no-build", action="store_true", help="skip the simulator build step")
        parser.add_argument("--max-cycles", type=int, default=1_000_000, help="timeout passed to the simulator")
        parser.add_argument("--filter", default="", help="only run cases whose name contains this substring")
        parser.add_argument(
            "--compare",
            action="store_true",
            help="convert RVFI commit logs to riscv-dv CSV and compare against spike.log when available",
        )
        parser.add_argument(
            "--riscv-dv-root",
            default=str(DEFAULT_RISCV_DV_ROOT),
            help="path to the local riscv-dv checkout that provides compare scripts",
        )
        parser.add_argument(
            "--work-dir",
            default="",
            help="directory for logs and compare artifacts (default: sim_verilator/build/riscv_dv/<suite-name>)",
        )
        parser.add_argument(
            "--sim-arg",
            action="append",
            default=[],
            help="extra argument passed through to the simulator, can be repeated",
        )
        args = parser.parse_args()

        suite_root = Path(args.suite).resolve()
        sim_bin = Path(args.sim).resolve()
        build_script = Path(args.build_script).resolve()
        riscv_dv_root = Path(args.riscv_dv_root).resolve()
        work_dir = (
            Path(args.work_dir).resolve()
            if args.work_dir
            else (SIM_DIR / "build" / "riscv_dv" / suite_root.name).resolve()
        )

        if not args.no_build:
            ensure_built(sim_bin, build_script)

        if not sim_bin.exists():
            print(f"error: simulator not found: {sim_bin}", file=sys.stderr)
            return 1
        if not suite_root.exists():
            print(f"error: suite directory does not exist: {suite_root}", file=sys.stderr)
            return 1

        cases = discover_cases(suite_root)
        if args.filter:
            cases = [case for case in cases if args.filter in case.name]
        if not cases:
            print(f"error: no runnable .bin/.elf/.o cases found under {suite_root}", file=sys.stderr)
            return 1

        work_dir.mkdir(parents=True, exist_ok=True)
        print(colorize(f"Running {len(cases)} riscv-dv case(s) with {sim_bin}", "cyan"))
        print(f"  suite: {suite_root}")
        print(f"  artifacts: {work_dir}")
        if args.compare:
            print(f"  compare: enabled against spike logs from {riscv_dv_root}")

        run_pass = 0
        run_fail = 0
        compare_pass = 0
        compare_fail = 0
        compare_skip = 0
        compare_error = 0

        for index, case in enumerate(cases, start=1):
            result = run_case(
                case=case,
                sim_bin=sim_bin,
                work_dir=work_dir,
                max_cycles=args.max_cycles,
                extra_sim_args=args.sim_arg,
                compare=args.compare,
                riscv_dv_root=riscv_dv_root,
            )

            run_status = colorize("RUN-PASS", "green") if result.run_ok else colorize("RUN-FAIL", "red")
            line = f"[{index:>3}/{len(cases):>3}] {run_status} {result.case.name} ({result.run_elapsed:.2f}s)"
            if args.compare:
                compare_color = {
                    "pass": "green",
                    "fail": "red",
                    "skip": "yellow",
                    "error": "red",
                }[result.compare_status]
                line += " " + colorize(f"CMP-{result.compare_status.upper()}", compare_color)
            print(line)

            if result.run_ok:
                run_pass += 1
            else:
                run_fail += 1
                print(f"      {result.run_summary}")
                print(f"      log: {result.run_log}")

            if args.compare:
                if result.compare_status == "pass":
                    compare_pass += 1
                elif result.compare_status == "fail":
                    compare_fail += 1
                    print(f"      {result.compare_summary}")
                    if result.compare_log is not None:
                        print(f"      compare: {result.compare_log}")
                elif result.compare_status == "skip":
                    compare_skip += 1
                    print(f"      {result.compare_summary}")
                else:
                    compare_error += 1
                    print(f"      {result.compare_summary}")
                    if result.compare_log is not None:
                        print(f"      compare: {result.compare_log}")

        run_summary = f"Run summary: {run_pass} passed, {run_fail} failed, {len(cases)} total"
        print(colorize(run_summary, "green" if run_fail == 0 else "red"))

        if args.compare:
            compare_summary = (
                f"Compare summary: {compare_pass} passed, {compare_fail} failed, "
                f"{compare_skip} skipped, {compare_error} error"
            )
            print(colorize(compare_summary, "green" if compare_fail == 0 and compare_error == 0 else "red"))

        if run_fail != 0:
            return 1
        if args.compare and (compare_fail != 0 or compare_error != 0):
            return 2
        return 0
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
