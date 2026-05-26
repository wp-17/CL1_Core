#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
SELFTEST_DIR="${SCRIPT_DIR}/selftest"
BUILD_DIR="${SELFTEST_DIR}/build"
CORE_CASE_DIR="${BUILD_DIR}/core_cases"
HARNESS_CASE_DIR="${BUILD_DIR}/harness_cases"
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

artifact_name() {
  local src="$1"
  local file="${src##*/}"
  printf '%s\n' "${file%.*}"
}

discover_sources() {
  local dir
  for dir in "${SELFTEST_DIR}/core" "${SELFTEST_DIR}/harness" "${SELFTEST_DIR}/negative"; do
    if [[ -d "${dir}" ]]; then
      find "${dir}" -maxdepth 1 -type f \( -name '*.c' -o -name '*.S' -o -name '*.s' \) -print0
    fi
  done
}

case_group_for_source() {
  local src="$1"
  local rel="${src#${SELFTEST_DIR}/}"
  local name
  name=$(artifact_name "${src}")

  if [[ "${rel}" == negative/* || "${name}" == *_fail ]]; then
    printf '%s\n' negative
  elif [[ "${rel}" == core/* ]]; then
    printf '%s\n' core
  elif [[ "${rel}" == harness/* ]]; then
    printf '%s\n' harness
  else
    echo "error: cannot classify selftest source: ${src}" >&2
    return 1
  fi
}

case_dir_for_group() {
  local group="$1"
  case "${group}" in
    core) printf '%s\n' "${CORE_CASE_DIR}" ;;
    harness) printf '%s\n' "${HARNESS_CASE_DIR}" ;;
    negative) printf '%s\n' "${NEG_DIR}" ;;
    *)
      echo "error: unknown selftest group: ${group}" >&2
      return 1
      ;;
  esac
}

stage_cases() {
  local -a sources=("$@")
  local src name group dst ext

  rm -rf "${CORE_CASE_DIR}" "${HARNESS_CASE_DIR}" "${NEG_DIR}"
  mkdir -p "${CORE_CASE_DIR}" "${HARNESS_CASE_DIR}" "${NEG_DIR}"

  for src in "${sources[@]}"; do
    name=$(artifact_name "${src}")
    group=$(case_group_for_source "${src}")
    dst=$(case_dir_for_group "${group}")
    for ext in elf bin hex map dis; do
      ln -sf "../${name}.${ext}" "${dst}/${name}.${ext}"
    done
  done
}

main() {
  local -a sources=()
  local src name
  declare -A seen_names=()

  maybe_source_nix_env

  mkdir -p "${BUILD_DIR}"

  while IFS= read -r -d '' src; do
    sources+=("${src}")
  done < <(discover_sources | sort -z)

  if ((${#sources[@]} == 0)); then
    echo "error: no selftest sources found under ${SELFTEST_DIR}/{core,harness,negative}" >&2
    return 1
  fi

  for src in "${sources[@]}"; do
    name=$(artifact_name "${src}")
    if [[ -n "${seen_names[${name}]:-}" ]]; then
      echo "error: duplicate selftest artifact name '${name}':" >&2
      echo "  ${seen_names[${name}]}" >&2
      echo "  ${src}" >&2
      return 1
    fi
    seen_names["${name}"]="${src}"
  done

  for src in "${sources[@]}"; do
    link_test "$(artifact_name "${src}")" "${src}"
  done

  stage_cases "${sources[@]}"
  echo "[selftest] artifacts ready under ${BUILD_DIR}"
}

main "$@"
