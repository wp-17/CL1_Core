#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
BUILD_DIR="${SCRIPT_DIR}/build"
OBJ_DIR="${BUILD_DIR}/obj_dir"
TOP_MODULE="Cl1Top"
NIX_HELPER="${SCRIPT_DIR}/nix_env.sh"
CL1_TEST_MODE="${CL1_TEST_MODE:-bus}"
CL1_PLATFORM="${CL1_PLATFORM:-${CL1_ADDRESS_PROFILE:-simple_soc}}"

if [[ "${CL1_TEST_MODE}" != "bus" && "${CL1_TEST_MODE}" != "cache" ]]; then
  echo "[build] CL1_TEST_MODE must be 'bus' or 'cache', got '${CL1_TEST_MODE}'" >&2
  exit 1
fi

MODE_BUILD_DIR="${BUILD_DIR}/${CL1_TEST_MODE}"
OBJ_DIR="${MODE_BUILD_DIR}/obj_dir"
RTL_DIR="${ROOT_DIR}/vsrc/${CL1_TEST_MODE}"
RTL_FILE="${RTL_DIR}/Cl1Top.sv"
SIM_BIN="${MODE_BUILD_DIR}/cl1_verilator"
LEGACY_SIM_LINK="${BUILD_DIR}/cl1_verilator"

maybe_source_nix_env() {
  # shellcheck disable=SC1090
  source "${NIX_HELPER}"
  sim_verilator_source_nix_env "${ROOT_DIR}" "[build]" verilator c++ make
}

ensure_rtl() {
  echo "[build] generating ${RTL_FILE} for CL1_PLATFORM=${CL1_PLATFORM}"
  (
    cd "${ROOT_DIR}"
    make verilog VSRC_DIR="${RTL_DIR}" CL1_TEST_MODE="${CL1_TEST_MODE}" CL1_PLATFORM="${CL1_PLATFORM}"
  )
}

build_sim() {
  rm -rf "${OBJ_DIR}"
  mkdir -p "${MODE_BUILD_DIR}"

  echo "[build] compiling Verilator model for CL1_TEST_MODE=${CL1_TEST_MODE}, CL1_PLATFORM=${CL1_PLATFORM}"
  local cflags="-std=c++17 -O2 -DCL1_TEST_MODE_${CL1_TEST_MODE^^}=1"
  local sources=(
    "${SCRIPT_DIR}/main.cpp"
    "${SCRIPT_DIR}/common.cpp"
    "${SCRIPT_DIR}/random_irq.cpp"
    "${SCRIPT_DIR}/options.cpp"
    "${SCRIPT_DIR}/memory_model.cpp"
    "${SCRIPT_DIR}/simulator.cpp"
  )
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
    -CFLAGS "${cflags}" \
    -o "cl1_verilator" \
    "${RTL_FILE}" \
    "${sources[@]}"

  ln -sf "${OBJ_DIR}/cl1_verilator" "${SIM_BIN}"
  if [[ "${CL1_TEST_MODE}" == "bus" ]]; then
    ln -sf "bus/cl1_verilator" "${LEGACY_SIM_LINK}"
  fi
  echo "[build] simulator ready: ${SIM_BIN}"
}

main() {
  maybe_source_nix_env
  ensure_rtl
  build_sim
}

main "$@"
