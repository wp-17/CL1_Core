#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
SELFTEST_DIR="${SCRIPT_DIR}/selftest"
BUILD_DIR="${SELFTEST_DIR}/build"
PASS_DIR="${BUILD_DIR}/pass_cases"
NEG_DIR="${BUILD_DIR}/negative_cases"
NIX_HELPER="${SCRIPT_DIR}/nix_env.sh"

maybe_source_nix_env() {
  # shellcheck disable=SC1090
  source "${NIX_HELPER}"
  sim_verilator_source_nix_env "${ROOT_DIR}" "[selftest]" riscv32-none-elf-gcc riscv32-none-elf-objcopy python3
}

bin_to_hex() {
  local bin_path="$1"
  local hex_path="$2"
  python3 - "$bin_path" "$hex_path" <<'PY'
import sys
from pathlib import Path

bin_path = Path(sys.argv[1])
hex_path = Path(sys.argv[2])
data = bin_path.read_bytes()
if len(data) % 4:
    data += b"\x00" * (4 - (len(data) % 4))

with hex_path.open("w", encoding="utf-8") as f:
    for i in range(0, len(data), 4):
        word = int.from_bytes(data[i:i + 4], "little")
        f.write(f"{word:08x}\n")
PY
}

link_test() {
  local name="$1"
  local c_src="$2"
  local elf="${BUILD_DIR}/${name}.elf"
  local bin="${BUILD_DIR}/${name}.bin"
  local hex="${BUILD_DIR}/${name}.hex"
  local map="${BUILD_DIR}/${name}.map"
  local dis="${BUILD_DIR}/${name}.dis"

  riscv32-none-elf-gcc \
    -march=rv32imc_zicsr \
    -mabi=ilp32 \
    -Os \
    -ffreestanding \
    -fno-pic \
    -fno-stack-protector \
    -fno-asynchronous-unwind-tables \
    -nostdlib \
    -nostartfiles \
    -Wall \
    -Wextra \
    -Werror \
    -I"${SELFTEST_DIR}" \
    "${SELFTEST_DIR}/crt0.S" \
    "${c_src}" \
    -Wl,-T,"${SELFTEST_DIR}/linker.ld" \
    -Wl,-Map,"${map}" \
    -Wl,--gc-sections \
    -o "${elf}"

  riscv32-none-elf-objcopy -O binary "${elf}" "${bin}"
  riscv32-none-elf-objdump -d "${elf}" > "${dis}"
  bin_to_hex "${bin}" "${hex}"
}

stage_cases() {
  rm -rf "${PASS_DIR}" "${NEG_DIR}"
  mkdir -p "${PASS_DIR}" "${NEG_DIR}"

  for name in host_exit_pass tohost_pass ebreak_pass custom_mmio_pass illegal_instruction_pass; do
    for ext in elf bin hex map dis; do
      ln -sf "../${name}.${ext}" "${PASS_DIR}/${name}.${ext}"
    done
  done

  for name in host_exit_fail; do
    for ext in elf bin hex map dis; do
      ln -sf "../${name}.${ext}" "${NEG_DIR}/${name}.${ext}"
    done
  done
}

main() {
  maybe_source_nix_env

  mkdir -p "${BUILD_DIR}"

  link_test "host_exit_pass" "${SELFTEST_DIR}/host_exit_pass.c"
  link_test "host_exit_fail" "${SELFTEST_DIR}/host_exit_fail.c"
  link_test "tohost_pass" "${SELFTEST_DIR}/tohost_pass.c"
  link_test "ebreak_pass" "${SELFTEST_DIR}/ebreak_pass.c"
  link_test "custom_mmio_pass" "${SELFTEST_DIR}/custom_mmio_pass.c"
  link_test "illegal_instruction_pass" "${SELFTEST_DIR}/illegal_instruction_pass.S"

  stage_cases
  echo "[selftest] artifacts ready under ${BUILD_DIR}"
}

main "$@"
