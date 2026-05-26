#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path


COMMIT_RE = re.compile(
    r"^CMT order=(?P<order>\d+)\s+"
    r"pc=0x(?P<pc>[0-9a-fA-F]+)\s+"
    r"insn=0x(?P<insn>[0-9a-fA-F]+)\s+"
    r"rd=(?P<rd>\d+)\s+"
    r"wdata=0x(?P<wdata>[0-9a-fA-F]+)\s+"
    r"trap=(?P<trap>\d+)\s*$"
)

ABI_NAMES = [
    "zero",
    "ra",
    "sp",
    "gp",
    "tp",
    "t0",
    "t1",
    "t2",
    "s0",
    "s1",
    "a0",
    "a1",
    "a2",
    "a3",
    "a4",
    "a5",
    "a6",
    "a7",
    "s2",
    "s3",
    "s4",
    "s5",
    "s6",
    "s7",
    "s8",
    "s9",
    "s10",
    "s11",
    "t3",
    "t4",
    "t5",
    "t6",
]

CSV_FIELDS = ["pc", "instr", "gpr", "csr", "binary", "mode", "instr_str", "operand", "pad"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert CL1 Verilator RVFI commit log to riscv-dv trace CSV")
    parser.add_argument("--log", required=True, help="input commit log from cl1_verilator --commit-log")
    parser.add_argument("--csv", required=True, help="output riscv-dv style CSV path")
    args = parser.parse_args()

    log_path = Path(args.log)
    csv_path = Path(args.csv)
    csv_path.parent.mkdir(parents=True, exist_ok=True)

    with log_path.open("r", encoding="utf-8") as src, csv_path.open("w", encoding="utf-8", newline="") as dst:
        writer = csv.DictWriter(dst, fieldnames=CSV_FIELDS)
        writer.writeheader()

        for lineno, line in enumerate(src, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            match = COMMIT_RE.match(stripped)
            if not match:
                raise SystemExit(f"unrecognized commit log format at {log_path}:{lineno}: {stripped}")

            rd = int(match.group("rd"), 10)
            gpr_update = ""
            if rd != 0:
                gpr_update = f"{ABI_NAMES[rd]}:{match.group('wdata').lower()[0:8].zfill(8)}"

            writer.writerow(
                {
                    "pc": match.group("pc").lower()[0:8].zfill(8),
                    "instr": "",
                    "gpr": gpr_update,
                    "csr": "",
                    "binary": match.group("insn").lower()[0:8].zfill(8),
                    "mode": "3",
                    "instr_str": "",
                    "operand": "",
                    "pad": "",
                }
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
