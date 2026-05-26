#!/usr/bin/env bash

set -euo pipefail

SIM_VERILATOR_ENV_CACHE_VERSION=1

sim_verilator_log() {
  local prefix="$1"
  shift
  printf '%s %s\n' "${prefix}" "$*" >&2
}

sim_verilator_required_tools_missing() {
  local tool
  for tool in "$@"; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      printf '%s\n' "${tool}"
      return 0
    fi
  done
  return 1
}

sim_verilator_env_fingerprint() {
  local root_dir="$1"
  local fingerprint_input
  fingerprint_input=$(
    printf 'cache-version:%s\n' "${SIM_VERILATOR_ENV_CACHE_VERSION}"
    cksum "${root_dir}/flake.nix"
    if [[ -f "${root_dir}/flake.lock" ]]; then
      cksum "${root_dir}/flake.lock"
    fi
  )
  printf '%s\n' "${fingerprint_input}" | cksum | awk '{print $1 ":" $2}'
}

sim_verilator_refresh_cached_env() {
  local root_dir="$1"
  local cache_script="$2"
  local cache_key="$3"
  local fingerprint="$4"
  local prefix="$5"
  local raw_env
  local clean_env
  local status

  raw_env=$(mktemp)
  clean_env=$(mktemp)

  sim_verilator_log "${prefix}" "refreshing cached Nix development environment"
  if nix print-dev-env "path:${root_dir}" > "${raw_env}"; then
    :
  else
    status=$?
    rm -f "${raw_env}" "${clean_env}"
    return "${status}"
  fi

  if sed '/^shellHook=.*/,/^export shellHook$/d;/^eval "${shellHook:-}"$/d' "${raw_env}" > "${clean_env}"; then
    :
  else
    status=$?
    rm -f "${raw_env}" "${clean_env}"
    return "${status}"
  fi

  mv "${clean_env}" "${cache_script}"
  printf '%s\n' "${fingerprint}" > "${cache_key}"
  rm -f "${raw_env}"
}

sim_verilator_source_nix_env() {
  local root_dir="$1"
  local prefix="$2"
  shift 2
  local required_tools=("$@")
  local missing_tool
  local cache_dir
  local cache_script
  local cache_key
  local fingerprint
  local cached_fingerprint

  if [[ -n "${IN_NIX_SHELL:-}" ]]; then
    return 0
  fi

  if ! command -v nix >/dev/null 2>&1; then
    missing_tool=$(sim_verilator_required_tools_missing "${required_tools[@]}") || true
    if [[ -n "${missing_tool:-}" ]]; then
      sim_verilator_log "${prefix}" "error: nix is unavailable and required tool '${missing_tool}' is missing"
      return 1
    fi
    return 0
  fi

  cache_dir="${root_dir}/sim_verilator/.cache"
  cache_script="${cache_dir}/nix-dev-env.sh"
  cache_key="${cache_dir}/nix-dev-env.key"
  mkdir -p "${cache_dir}"

  fingerprint=$(sim_verilator_env_fingerprint "${root_dir}")
  cached_fingerprint=""
  if [[ -f "${cache_key}" ]]; then
    cached_fingerprint=$(<"${cache_key}")
  fi

  if [[ "${SIM_VERILATOR_FORCE_NIX_ENV_REFRESH:-0}" == "1" ]] || [[ ! -f "${cache_script}" ]] || [[ "${cached_fingerprint}" != "${fingerprint}" ]]; then
    sim_verilator_refresh_cached_env "${root_dir}" "${cache_script}" "${cache_key}" "${fingerprint}" "${prefix}"
  fi

  # shellcheck disable=SC1090
  source "${cache_script}"

  missing_tool=$(sim_verilator_required_tools_missing "${required_tools[@]}") || true
  if [[ -n "${missing_tool:-}" ]]; then
    sim_verilator_log "${prefix}" "error: tool '${missing_tool}' is still unavailable after sourcing the cached environment"
    return 1
  fi
}
