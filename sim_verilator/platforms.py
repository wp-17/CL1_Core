#!/usr/bin/env python3

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class SimRegion:
    name: str
    base: int
    size: int
    permissions: str

    def sim_arg(self) -> str:
        return f"{self.name}:0x{self.base:08x}:0x{self.size:x}:{self.permissions}"


@dataclass(frozen=True)
class Platform:
    name: str
    boot_addr: int
    trap_vector: int
    ram_base: int
    ram_size: int
    load_addr: int
    uart_addr: int | None = None
    host_exit_addr: int | None = None
    simulator_regions: tuple[SimRegion, ...] = ()
    riscv_dv_regions: tuple[SimRegion, ...] = ()
    selftest_regions: tuple[SimRegion, ...] = ()

    def sim_args(self, *, load_addr: int | str | None = None) -> list[str]:
        args = [
            "--ram-base",
            hex32(self.ram_base),
            "--ram-size",
            str(self.ram_size),
            "--load-addr",
            hex32(int_value(load_addr) if load_addr is not None else self.load_addr),
        ]
        if self.uart_addr is not None:
            args.extend(["--uart-addr", hex32(self.uart_addr)])
        if self.host_exit_addr is not None:
            args.extend(["--host-exit-addr", hex32(self.host_exit_addr)])
        for region in self.simulator_regions:
            args.extend(["--region", region.sim_arg()])
        return args

    def riscv_dv_sim_args(self) -> list[str]:
        return [arg for region in self.riscv_dv_regions for arg in ("--region", region.sim_arg())]

    def selftest_region_args(self, name: str) -> list[str]:
        matches = [region for region in self.selftest_regions if region.name == name]
        return [arg for region in matches for arg in ("--region", region.sim_arg())]


def hex32(value: int) -> str:
    return f"0x{value & 0xffffffff:08x}"


def int_value(value: str | int) -> int:
    if isinstance(value, int):
        return value
    return int(str(value), 0)


def normalize_platform_name(name: str) -> str:
    normalized = name.strip().lower().replace("-", "_")
    aliases = {
        "simple": "simple_soc",
        "simple_soc": "simple_soc",
        "full": "full_soc",
        "full_soc": "full_soc",
    }
    try:
        return aliases[normalized]
    except KeyError as exc:
        raise ValueError(f"unknown CL1 platform: {name}") from exc


PLATFORMS: dict[str, Platform] = {
    "simple_soc": Platform(
        name="simple_soc",
        boot_addr=0x80000000,
        trap_vector=0x20000000,
        ram_base=0x80000000,
        ram_size=0x01000000,
        load_addr=0x80000000,
        uart_addr=0x10000000,
        host_exit_addr=0x10000004,
        riscv_dv_regions=(
            SimRegion("spike_high_read", 0xFFFF0000, 0x00010000, "r--"),
        ),
        selftest_regions=(
            SimRegion("test_region", 0x60000000, 0x00000004, "rw-"),
        ),
    ),
    "full_soc": Platform(
        name="full_soc",
        boot_addr=0x01000000,
        trap_vector=0x20000000,
        ram_base=0x80000000,
        ram_size=0x20000000,
        load_addr=0x01000000,
        uart_addr=0x10010000,
        simulator_regions=(
            SimRegion("isram", 0x01000000, 0x00040000, "rwx"),
            SimRegion("dsram", 0x01800000, 0x00004000, "rw-"),
            SimRegion("qspi_mem", 0x20000000, 0x01000000, "rwx"),
        ),
    ),
}


def active_platform_name(requested: str | None = None) -> str:
    raw = (
        requested
        or os.environ.get("CL1_PLATFORM", "")
        or os.environ.get("CL1_ADDRESS_PROFILE", "")
        or "simple_soc"
    )
    return normalize_platform_name(raw)


def active_platform(requested: str | None = None) -> Platform:
    return PLATFORMS[active_platform_name(requested)]
