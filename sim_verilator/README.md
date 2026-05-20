# Verilator Batch Simulation

This directory contains a complete Verilator-based simulation flow for the generated `Cl1Top` RTL.

The goal of this flow is to let you:

- build a runnable Verilator model directly from the Chisel-generated top module
- load `ELF`, `BIN`, or `HEX` test programs without modifying internal RTL memory arrays
- stop the simulation cleanly when software reports completion
- run a whole `tests/` directory in batch and get a clear PASS/FAIL summary
- run a built-in selectable regression suite based on tiny bare-metal C programs

## What Was Implemented

### 1. Verilator top-level testbench

`main.cpp` is the simulation entry point.

It is responsible for:

- instantiating `VCl1Top`
- generating clock and reset
- driving the exposed `io_ibus_*` and `io_dbus_*` interfaces
- modeling memory and simple MMIO in C++
- optionally dumping FST waveforms
- returning a process exit code that batch scripts can use

### 2. Memory loading in C++ instead of DPI-C

The current RTL is generated with `EXPOSE_CORE_BUS=true`, so `Cl1Top` already exposes:

- `io_ibus_*`
- `io_dbus_*`

Because of that, the cleanest solution is not DPI-C access into internal Chisel memories.
Instead, the simulator models memory at the bus boundary in C++.

This has two advantages:

- it avoids dependence on internal SRAM instance names or Chisel emission details
- it keeps the simulation flow stable even if internal memory implementation changes later

Supported image formats:

- `ELF`: loads `PT_LOAD` segments and also extracts `tohost` / `fromhost` symbols
- `BIN`: loads raw bytes at `--load-addr`, default `0x80000000`
- `HEX`: supports sequential hex data and `@address` directives

### 3. Simulation termination mechanism

The processor itself does not stop automatically, so the simulator implements software-visible stop conditions.

The simulation exits when any of the following happens:

1. a write to `tohost` is detected from ELF symbol metadata
2. a write to the host exit MMIO register at `0x10000004` is detected
3. a committed `ebreak` is observed through RVFI

For the `ebreak` path, the simulator uses a shadow GPR model and interprets:

- `a0 == 0` as PASS
- `a0 != 0` as FAIL

### 4. Batch regression runner

`run_tests.py` scans a directory tree for:

- `.elf`
- `.bin`
- `.hex`

It groups files by basename, prefers `ELF` over `BIN` over `HEX`, runs each test, prints a colored PASS/FAIL line, writes a per-test log, and prints a final summary.

It also supports `--prefer-image-type <elf|bin|hex>`, which is useful when the same regression case exists in multiple formats and you want to force one loader path.

### 5. Built-in self-test and regression flow

A small bare-metal self-test set was added under `sim_verilator/selftest/`.

These test programs are intentionally simple and are built from C plus a minimal startup file:

- `host_exit_pass.c`: writes PASS through the default host-exit MMIO register
- `host_exit_fail.c`: writes a failing exit code
- `tohost_pass.c`: validates ELF symbol-based `tohost` termination
- `ebreak_pass.c`: validates the `ebreak` stop path
- `custom_mmio_pass.c`: validates custom `--host-exit-addr` and `--uart-addr`
- `access_fault_pass.S`: reads and writes an unmapped address and checks load/store access-fault traps
- `config_region_pass.S`: writes and reads an extra simulator region added with `--region`
- `interrupt_external_pass.S`: waits for a randomly injected machine external interrupt
- `interrupt_software_pass.S`: waits for a randomly injected machine software interrupt
- `interrupt_timer_pass.S`: waits for a randomly injected machine timer interrupt

Two extra scripts were added for this:

- `build_test_programs.sh`: builds the self-test `ELF` / `BIN` / `HEX` artifacts
- `regression.py`: runs selectable regression suites over the simulator and self-tests

## Files

- `main.cpp`: Verilator harness, memory model, image loader, stop logic
- `build.sh`: prepares toolchain access, regenerates RTL if needed, and builds the simulator
- `build_test_programs.sh`: builds bare-metal regression test artifacts
- `run_tests.py`: batch regression driver
- `regression.py`: selectable direct and batch regression runner
- `Makefile`: short wrapper for build, single-run, and batch-run commands
- `selftest/`: startup code, linker script, and simple C self-tests

## What Happens Before Running

When you execute `./sim_verilator/build.sh`, it does the following:

1. If you are already inside `nix develop`, it reuses that shell directly and does not evaluate Nix again.
2. Otherwise, it uses a cached `nix print-dev-env` result from `sim_verilator/.cache/`.
3. The cache is refreshed only when `flake.nix` or `flake.lock` changes, or when you set `SIM_VERILATOR_FORCE_NIX_ENV_REFRESH=1`.
4. It strips the flake `shellHook` before sourcing, so the build stays non-interactive.
5. It checks whether `vsrc/Cl1Top.sv` already exists.
6. If the generated RTL is missing, it runs `make verilog`.
7. It calls Verilator on `vsrc/Cl1Top.sv` and compiles `main.cpp`.
8. It creates the runnable simulator binary at `sim_verilator/build/cl1_verilator`.

So in normal use, you do not need to manually run Verilator yourself.

When you execute `./sim_verilator/build_test_programs.sh`, it does the following:

1. It reuses the same fast-path behavior as `build.sh`: active `nix develop` shell first, otherwise cached environment.
2. It compiles the bare-metal self-tests with `riscv32-none-elf-gcc`.
3. It emits `ELF`, `BIN`, `HEX`, map, and disassembly files.
4. It stages reusable pass/fail case directories under `sim_verilator/selftest/build/`.

## Fast Iteration Tips

If you are repeatedly editing and rebuilding, use one of these two workflows:

- Best latency: enter `nix develop` once, stay in that shell, and run `make`, `build.sh`, or `regression.py` from there
- Good latency without an interactive dev shell: run the scripts normally and let `sim_verilator/.cache/nix-dev-env.sh` avoid repeated Nix evaluation

Examples:

```bash
nix develop
./sim_verilator/build.sh
./sim_verilator/regression.py --suite smoke
```

If you change `flake.nix` or `flake.lock`, the cache refreshes automatically.
If you want to refresh it manually:

```bash
SIM_VERILATOR_FORCE_NIX_ENV_REFRESH=1 ./sim_verilator/build.sh
```

One repo-level improvement is still worth considering outside `sim_verilator`: the current `flake.nix` `shellHook` runs `exec zsh`. That is convenient for interactive use, but it complicates automation enough that these scripts must strip the hook before sourcing the environment.

## MMIO Model

The current C++ model provides two basic MMIO locations:

- UART TX register: `0x10000000`
- Host exit register: `0x10000004`

Behavior:

- writes to `0x10000000` are printed to stdout as characters
- writes to `0x10000004` terminate simulation
- exit value `0` or `1` is treated as PASS
- any other non-zero value is treated as FAIL

## Recommended Software Exit Convention

For the most reliable batch flow, prefer one of these software-side conventions:

- use an ELF with a `tohost` symbol
- write a completion code to `0x10000004`

The MMIO host-exit path is the simplest option for small standalone tests.

## How To Run

### Build the simulator

```bash
./sim_verilator/build.sh
```

Equivalent:

```bash
make -C sim_verilator build
```

### Run a single test image

Minimal example:

```bash
./sim_verilator/build/cl1_verilator path/to/test.bin
```

Common examples:

```bash
./sim_verilator/build/cl1_verilator path/to/test.elf
./sim_verilator/build/cl1_verilator --load-addr 0x80000000 path/to/test.bin
./sim_verilator/build/cl1_verilator --trace wave.fst path/to/test.elf
./sim_verilator/build/cl1_verilator --symbol-elf path/to/test.elf path/to/test.bin
./sim_verilator/build/cl1_verilator --irq-lines ext --irq-seed 11 selftest/build/interrupt_external_pass.elf
```

Equivalent `make` wrapper:

```bash
make -C sim_verilator run IMAGE=path/to/test.bin
```

With extra simulator options:

```bash
make -C sim_verilator run IMAGE=path/to/test.bin SIM_ARGS="--max-cycles 200000 --trace run.fst"
```

### Run all tests in batch

If your tests are under `tests/`:

```bash
./sim_verilator/run_tests.py tests
```

Examples:

```bash
./sim_verilator/run_tests.py tests --max-cycles 200000
./sim_verilator/run_tests.py tests --filter coremark
./sim_verilator/run_tests.py tests --prefer-image-type bin
./sim_verilator/run_tests.py tests --prefer-image-type hex
./sim_verilator/run_tests.py tests --sim-arg=--verbose
./sim_verilator/run_tests.py tests --sim-arg=--trace --sim-arg=case.fst
```

Equivalent `make` wrapper:

```bash
make -C sim_verilator test TESTS=tests
```

### Run a `riscv-dv` output directory

If `../riscv-dv` has already generated and compiled cases, point the CL1 runner at that suite directory:

```bash
./sim_verilator/run_riscv_dv.py ../riscv-dv/verification_output/rv32imc_mmode_directed_suite
```

The runner discovers `*.bin` plus a matching sidecar `*.o` or `*.elf`, then invokes the simulator with `--symbol-elf` automatically.

With the `make` wrapper:

```bash
make -C sim_verilator riscv-dv SUITE=../riscv-dv/verification_output/rv32imc_mmode_directed_suite
```

To run every runnable suite under `verification_output`, pass the `verification_output` root instead:

```bash
make -C sim_verilator riscv-dv SUITE=../riscv-dv/verification_output
```

To also compare the RTL RVFI trace against the suite's `spike.log` files:

```bash
make -C sim_verilator riscv-dv \
  SUITE=../riscv-dv/verification_output/rv32imc_mmode_directed_suite \
  RVDV_ARGS="--compare"
```

The same `--compare` option works when `SUITE` is the `verification_output` root.

## Batch Runner Behavior

The batch script does the following for each test:

1. choose one image for each test stem
2. choose the preferred format requested by `--prefer-image-type`, then fall back to the remaining formats
3. pass a sidecar ELF as `--symbol-elf` when the chosen image is not ELF
4. collect stdout and stderr into `sim_verilator/build/test_logs/*.log`
5. treat simulator exit code `0` as PASS
6. treat any non-zero exit code as FAIL

Terminal output format:

- PASS lines are green
- FAIL lines are red
- a final summary shows passed / failed / total

## Useful Simulator Options

`main.cpp` supports the following commonly useful options:

- `--image-type <auto|elf|bin|hex>`
- `--symbol-elf <path>`
- `--load-addr <addr>`
- `--ram-base <addr>`
- `--ram-size <bytes>`
- `--region <name:base:size[:rwx]>`
- `--irq-lines <ext:sft:tmr|all>`
- `--irq-seed <value>`
- `--irq-delay <min:max>`
- `--irq-width <min:max>`
- `--host-exit-addr <addr>`
- `--uart-addr <addr>`
- `--max-cycles <count>`
- `--trace <path.fst>`
- `--quiet`
- `--verbose`

## Example Test Layout

Example:

```text
tests/
├── hello.elf
├── memcpy.bin
├── memcpy.elf
├── smoke.hex
└── subdir/
    └── branch.bin
```

Batch grouping behavior:

- `hello.elf` runs as `hello`
- with default settings, `memcpy.elf` is preferred over `memcpy.bin`
- with `--prefer-image-type bin`, `memcpy.bin` is chosen and `memcpy.elf` is passed as `--symbol-elf`
- `smoke.hex` runs as `smoke`
- `subdir/branch.bin` runs as `subdir/branch`

## Built-In Regression

### Build the self-test programs

```bash
./sim_verilator/build_test_programs.sh
```

Equivalent:

```bash
make -C sim_verilator selftest
```

### Run the selectable regression suites

Available suites:

- `smoke`: minimal quick sanity check
- `direct`: direct feature checks against the simulator CLI
- `batch`: batch-runner checks across `ELF`, `BIN`, `HEX`, and a negative batch case
- `full`: `direct` plus `batch`

Examples:

```bash
./sim_verilator/regression.py --suite smoke
./sim_verilator/regression.py --suite direct
./sim_verilator/regression.py --suite batch
./sim_verilator/regression.py --suite full
```

Equivalent `make` wrapper:

```bash
make -C sim_verilator regression
make -C sim_verilator regression SUITE=direct
make -C sim_verilator regression SUITE=batch
make -C sim_verilator regression SUITE=full
```

### What the full regression validates

The `full` suite checks:

- simulator help output
- ELF loading
- BIN loading
- HEX loading
- sidecar ELF symbol parsing for raw images
- trace file generation
- verbose mode
- quiet mode
- custom MMIO base options
- configurable simulator address regions and unmapped load/store access faults
- random machine external/software/timer interrupt injection
- `tohost` termination
- host-exit MMIO termination
- `ebreak` termination
- fail return-code handling
- batch PASS summary for `ELF`
- batch PASS summary for `BIN`
- batch PASS summary for `HEX`
- batch negative summary with an expected FAIL case

## Verification Status

The flow has been validated with the built-in regression suite.

- simulator build through `./sim_verilator/build.sh`
- self-test build through `./sim_verilator/build_test_programs.sh`
- direct feature regression through `./sim_verilator/regression.py --suite direct`
- batch regression through `./sim_verilator/regression.py --suite batch`
- full regression through `./sim_verilator/regression.py --suite full`

Latest verified result:

- `./sim_verilator/regression.py --suite full --no-build-sim --no-build-tests --max-cycles 5000`: `21 passed, 0 failed`

Recommended software contract remains:

- prefer `tohost` or host-exit MMIO for normal test completion
- keep `ebreak` as an additional supported stop path
