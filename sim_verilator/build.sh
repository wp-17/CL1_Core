#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
BUILD_DIR="${SCRIPT_DIR}/build"
OBJ_DIR="${BUILD_DIR}/obj_dir"
RTL_FILE="${ROOT_DIR}/vsrc/Cl1Top.sv"
SIM_BIN="${BUILD_DIR}/cl1_verilator"
TOP_MODULE="Cl1Top"
NIX_HELPER="${SCRIPT_DIR}/nix_env.sh"

maybe_source_nix_env() {
  # shellcheck disable=SC1090
  source "${NIX_HELPER}"
  sim_verilator_source_nix_env "${ROOT_DIR}" "[build]" verilator c++ make
}

ensure_rtl() {
  if [[ -f "${RTL_FILE}" ]]; then
    return
  fi

  echo "[build] generating ${RTL_FILE}"
  (
    cd "${ROOT_DIR}"
    make verilog
  )
}

build_sim() {
  mkdir -p "${BUILD_DIR}"

  echo "[build] compiling Verilator model"
  verilator \
    --cc \
    --exe \
    --build \
    --timing \
    --trace-fst \
    --top-module "${TOP_MODULE}" \
    --Mdir "${OBJ_DIR}" \
    -Wall \
    --Wno-fatal \
    --Wno-UNOPTFLAT \
    --Wno-BLKANDNBLK \
    --Wno-CASEINCOMPLETE \
    -CFLAGS "-std=c++17 -O2" \
    -o "cl1_verilator" \
    "${RTL_FILE}" \
    "${SCRIPT_DIR}/main.cpp"

  ln -sf "${OBJ_DIR}/cl1_verilator" "${SIM_BIN}"
  echo "[build] simulator ready: ${SIM_BIN}"
}

main() {
  maybe_source_nix_env
  ensure_rtl
  build_sim
}

main "$@"
