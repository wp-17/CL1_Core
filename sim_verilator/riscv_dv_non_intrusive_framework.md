# Non-Intrusive `riscv-dv` Adaptation and Debugging Framework for CL1 Verilator

## Scope

This document defines a non-intrusive integration flow between:

- the local `../riscv-dv` generator
- the CL1 Verilator model in `sim_verilator/`
- Spike as the architectural reference

The hard rule is preserved: **no source changes inside `../riscv-dv`**. All adaptation is done with:

- external wrapper scripts
- custom compile/link commands
- a standalone BSP (`crt0.S`, `linker.ld`)
- sidecar trace conversion scripts

## Repo-Specific Facts That Shape The Design

- The current CL1 standalone boot address is `0x80000000`.
  Source: `cl1/src/scala/Cl1Config.scala`
- The current trap-vector reset value is `0x20000000`.
  Source: `cl1/src/scala/Cl1Config.scala`
- The current Verilator harness models:
  - RAM base `0x80000000`
  - RAM size `16 MiB`
  - UART TX MMIO `0x10000000`
  - host-exit MMIO `0x10000004`
  Source: `sim_verilator/main.cpp`
- The current Verilator harness already recognizes three software-visible termination paths:
  - non-zero `tohost` write from ELF symbol metadata
  - MMIO write to `0x10000004`
  - committed `ebreak`
  Source: `sim_verilator/main.cpp`
- The top-level already exposes RVFI signals, so architectural commit extraction is available without touching `riscv-dv`.
  Source: `cl1/src/scala/Cl1Top.scala`
- The current local `riscv-dv` target is machine-mode-only `rv32imc`.
  Source: `../riscv-dv/pygen/pygen_src/target/rv32imc_mmode_trap/riscv_core_setting.py`

There are also four important contract risks in the current workspace that must be part of triage:

- the RTL `misa` constant is `0x40000104`, which advertises `RV32IC`, not `RV32IMC`
  Source: `cl1/src/scala/Cl1CSR.scala`
- `mtval` is listed in the local `riscv-dv` target as implemented, but the RTL CSR block does not implement CSR `0x343`.
  Source: `../riscv-dv/pygen/pygen_src/target/rv32imc_mmode_trap/riscv_core_setting.py`, `cl1/src/scala/Cl1CSR.scala`
- The LSU still contains `TODO:add misaligned memory access exception`.
  Source: `cl1/src/scala/Cl1LSU.scala`
- The decoder marks illegal instructions, but there is no visible illegal-instruction exception path connected into `Cl1EXCP`.
  Source: `cl1/src/scala/Cl1Decoder.scala`, `cl1/src/scala/Cl1EXCP.scala`

Those are not flow problems. They are boundary-definition problems and must be separated from infrastructure failures.

## 1. Non-intrusive Execution Adaptation Flow

### 1.1 Design Principle

Do not let `riscv-dv` own final linking for the DUT. Let it stop at generated assembly, then take over externally.

Recommended top-level flow:

1. Run `riscv-dv` in generation-only mode.
2. Collect the emitted `.S` files from the chosen output directory.
3. Build DUT-specific `ELF/BIN/HEX` artifacts with external scripts.
4. Run Spike on the externally linked ELF.
5. Run Verilator on the same ELF or on `BIN/HEX` plus `--symbol-elf`.

This keeps:

- test generation inside `riscv-dv`
- board adaptation outside `riscv-dv`
- architectural reference and DUT running the same linked image

### 1.2 Two Supported Integration Modes

The local `riscv-dv` output is important here: the generated `.S` already contains:

- `_start`
- machine-mode setup
- `mtvec_handler`
- `test_done`
- `write_tohost`
- `tohost/fromhost`

That means there are two valid non-intrusive strategies.

| Mode | When to use | Main idea |
| --- | --- | --- |
| Native generated-program mode | First bring-up for the current local `riscv-dv` tree | Keep the generated startup/trap code. Only replace the linker script and final compilation flow. |
| Wrapper BSP mode | When you need one uniform board contract across all tests | Rename the generated `_start` symbol externally and insert a custom `crt0.S` in front of it. |

For the current repo, **start with native generated-program mode**. It matches the emitted `.S` shape and uses the existing `tohost` protocol already supported by the simulator.

Use **wrapper BSP mode** when you need:

- a fixed board stack and memory layout
- a default trap vector at `0x20000000`
- a uniform `ecall -> MMIO host-exit` convention
- early-boot failure capture before the generated handler installs its own `mtvec`

### 1.3 Assembly Interception Pipeline

Recommended external driver layout:

1. Generate tests:

```bash
python3 ../riscv-dv/run.py \
  --simulator pyflow \
  --target rv32imc_mmode_trap \
  --testlist <your_testlist.yaml> \
  --test all \
  --steps gen \
  --output <workdir/riscvdv_out>
```

2. Discover generated assembly:

- Scan `<workdir/riscvdv_out>/asm_test/*.S`
- Treat each `.S` file as the single source of truth for that test case
- Do not edit it in place

3. Build per-test artifacts under a CL1-owned directory, for example:

```text
<workdir>/cases/<test_name>/
  test.S
  test.o
  test.elf
  test.bin
  test.hex
  test.map
  test.dis
  spike.log
  spike.csv
  rtl.log
  rtl.csv
  compare.log
  wave.fst
```

### 1.4 Compilation And Linking Strategy

#### Native generated-program mode

This mode keeps the generated startup code and trap handler.

Compilation steps:

1. Assemble the generated file to an object.
2. Link it with a DUT-specific linker script.
3. Convert the linked ELF to `BIN/HEX`.

Representative command sequence:

```bash
riscv32-none-elf-gcc \
  -c \
  -march=rv32imc_zicsr_zifencei \
  -mabi=ilp32 \
  -mcmodel=medany \
  -ffreestanding \
  -nostdlib \
  -o test.o test.S

riscv32-none-elf-gcc \
  -march=rv32imc_zicsr_zifencei \
  -mabi=ilp32 \
  -mcmodel=medany \
  -nostdlib \
  -nostartfiles \
  -Wl,-T,linker.ld \
  -Wl,-Map,test.map \
  -Wl,--gc-sections \
  -o test.elf test.o

riscv32-none-elf-objcopy -O binary test.elf test.bin
riscv32-none-elf-objdump -d test.elf > test.dis
```

This is the lowest-risk path because it does not fight the structure of the emitted `riscv-dv` assembly.

#### Wrapper BSP mode

This mode inserts a custom `crt0.S` in front of the generated program.

The required trick is external symbol rewriting:

```bash
riscv32-none-elf-gcc \
  -c \
  -march=rv32imc_zicsr_zifencei \
  -mabi=ilp32 \
  -mcmodel=medany \
  -ffreestanding \
  -nostdlib \
  -o generated.o test.S

riscv32-none-elf-objcopy \
  --redefine-sym _start=dv_generated_start \
  generated.o generated_wrapped.o
```

Then link:

```bash
riscv32-none-elf-gcc \
  -march=rv32imc_zicsr_zifencei \
  -mabi=ilp32 \
  -mcmodel=medany \
  -nostdlib \
  -nostartfiles \
  -Wl,-T,linker.ld \
  -Wl,-Map,test.map \
  -Wl,--gc-sections \
  -o test.elf crt0.S generated_wrapped.o
```

This gives you a real board-owned `_start` while keeping the generated program untouched.

### 1.5 BSP Requirements

The BSP is a DUT-owned layer, not a `riscv-dv` layer.

#### `crt0.S` responsibilities

The custom `crt0.S` should do the following:

1. Initialize `sp` to a linker-defined top-of-stack in RAM.
2. Zero `.bss`.
3. Optionally initialize `gp` if the toolchain model requires it.
4. Install a **default** `mtvec` handler immediately.
5. Provide a trap stub placed at `0x20000000` so early exceptions are never invisible.
6. Jump to `dv_generated_start` in wrapper mode, or call a board-owned `main` for directed tests.
7. If control ever returns unexpectedly, terminate via host-exit MMIO with a fatal code.

Minimal structure:

```asm
.section .text.init
.globl _start
_start:
  la   sp, __stack_top

  la   t0, __bss_start
  la   t1, __bss_end
1:
  bgeu t0, t1, 2f
  sw   zero, 0(t0)
  addi t0, t0, 4
  j    1b

2:
  la   t0, default_trap
  csrw mtvec, t0

  call dv_generated_start

unexpected_return:
  li   a0, 0xdead0001
  j    __host_exit

.section .trap, "ax"
default_trap:
  csrr t0, mcause
  li   t1, 11
  beq  t0, t1, handle_ecall
  li   a0, 0xdead0000
  or   a0, a0, t0
  j    __host_exit

handle_ecall:
  mv   a0, gp
  j    __host_exit

__host_exit:
  li   t2, 0x10000004
  sw   a0, 0(t2)
1:
  j    1b
```

Important notes:

- In the **current** local `riscv-dv` flow, the generated program will later write its own `mtvec`. That is fine. The BSP trap stub is still valuable as an early-boot guardrail.
- If you want the BSP to remain the only trap owner, you must also externally redirect the generated trap symbols. That is possible, but it is more brittle than the native mode and should be used only when the board contract must dominate the generated handler.

#### `linker.ld` responsibilities

The linker script must encode the DUT-visible memory contract, not the `riscv-dv` default one.

For the current standalone Verilator harness, the memory contract is:

- executable/test RAM starts at `0x80000000`
- contiguous modelled RAM size is `16 MiB`
- default trap-vector reset address is `0x20000000`
- UART MMIO is `0x10000000`
- host-exit MMIO is `0x10000004`

Recommended section policy:

- place `.text.init`, `.text`, `.rodata`, `.data`, `.bss`, stacks in RAM
- place `.trap` at `0x20000000` only if wrapper mode is used
- keep `.tohost` and `.fromhost` in RAM and symbol-visible
- do not place general test data into unmodelled regions

Representative linker script:

```ld
ENTRY(_start)

MEMORY
{
  RAM   (rwx) : ORIGIN = 0x80000000, LENGTH = 16M
  TRAP  (rx)  : ORIGIN = 0x20000000, LENGTH = 4K
}

SECTIONS
{
  . = ORIGIN(RAM);

  .text.init : ALIGN(4)
  {
    KEEP(*(.text.init))
  } > RAM

  .text : ALIGN(4)
  {
    *(.text .text.*)
    *(.rodata .rodata.*)
  } > RAM

  .tohost : ALIGN(64)
  {
    KEEP(*(.tohost))
  } > RAM

  .fromhost : ALIGN(64)
  {
    KEEP(*(.fromhost))
  } > RAM

  .data : ALIGN(4)
  {
    *(.data .data.*)
    *(.sdata .sdata.*)
  } > RAM

  .bss (NOLOAD) : ALIGN(4)
  {
    __bss_start = .;
    *(.sbss .sbss.*)
    *(.bss .bss.*)
    *(COMMON)
    __bss_end = .;
  } > RAM

  .trap ORIGIN(TRAP) : ALIGN(4)
  {
    KEEP(*(.trap))
  } > TRAP

  . = ALIGN(16);
  . += 4096;
  __stack_top = .;

  /DISCARD/ :
  {
    *(.comment)
    *(.eh_frame*)
    *(.riscv.attributes)
    *(.debug*)
  }
}
```

Why keep `tohost` in RAM instead of forcing it to `0x10000004`?

- The current simulator already parses the ELF symbol and watches that address.
- The generated assembly declares `.dword tohost`; keeping it as a normal symbol avoids layout fights.
- You can still use MMIO host-exit as the board-standard path for wrapper-authored tests.

### 1.6 Termination Mapping

The termination contract should be standardized at the simulator boundary, not inside `riscv-dv`.

#### Preferred mapping for the current local generated tests

Use the existing `tohost` mechanism already present in the generated `.S`.

Rules:

- Run Verilator with the ELF whenever possible.
- If you run `BIN/HEX`, pass the ELF as `--symbol-elf` so the simulator still knows the `tohost` address.

Example:

```bash
./sim_verilator/build/cl1_verilator test.elf
./sim_verilator/build/cl1_verilator --symbol-elf test.elf test.bin
```

This requires no generated-code changes and fits the current workspace best.

#### Standardized wrapper mapping

For the wrapper BSP flow, translate software completion to:

- MMIO write to `0x10000004`

Recommended encoding:

| Software event | Written value | Simulator interpretation |
| --- | --- | --- |
| pass | `1` | PASS |
| pass from wrapper-owned tests | `0` or `1` | PASS |
| fail | non-zero, not `1` | FAIL |
| unexpected trap | `0xdead0000 | mcause` | FAIL with useful signature |

This aligns with the current simulator behavior in `sim_verilator/main.cpp`.

#### `ecall` interception

If the wrapper owns trap handling, `ecall` interception is straightforward:

1. `mtvec` points to the BSP trap stub.
2. Trap stub reads `mcause`.
3. If `mcause == 11` (`ECALL from M-mode`), move the chosen software status register into `a0`.
4. Write `a0` to host-exit MMIO.

Recommended status source:

- use `gp` for compatibility with the current `riscv-dv` generated programs
- use `a0` for wrapper-authored directed tests if you prefer the same convention as the simulator `ebreak` path

#### CSR-write-based termination

Do **not** invent a fake CSR as the primary termination mechanism for this core-level environment.

Reasons:

- the DUT does not expose a board-specific custom CSR contract
- absent CSR behavior is itself one of the verification questions
- MMIO host-exit and `tohost` are already available and easier to debug

If you encounter tests that signal completion by CSR writes inside their own trap handler, convert that to host-exit in the board-owned handler rather than teaching the simulator new CSR semantics.

## 2. Simulation And Trace Checking Flow

### 2.1 Goal

Use an external conversion flow so that:

- Spike remains the golden architectural reference
- the RTL trace is converted into the same CSV schema used by `riscv-dv`
- the existing `riscv-dv` comparator can be reused unchanged

### 2.2 Recommended End-to-End Flow

1. Generate the test assembly with `riscv-dv`.
2. Externally build `test.elf`, `test.bin`, `test.hex`, `test.dis`, `test.map`.
3. Run Spike on `test.elf`.
4. Convert the Spike log to CSV with `../riscv-dv/scripts/spike_log_to_trace_csv.py`.
5. Run Verilator on the same image.
6. Convert the RTL commit trace to the same CSV format.
7. Compare the two CSVs with `../riscv-dv/scripts/instr_trace_compare.py`.

### 2.3 ISS Side

Use the same Spike settings already captured in the local repo:

```bash
spike --log-commits --isa=rv32imc --priv=m -l test.elf
```

Then convert:

```bash
python3 ../riscv-dv/scripts/spike_log_to_trace_csv.py \
  --log spike.log \
  --csv spike.csv \
  --full_trace
```

This keeps the ISS side fully aligned with the local `riscv-dv` target and requires no `riscv-dv` edits.

### 2.4 RTL Side: Preferred Extraction Method

The preferred source for the RTL trace is **RVFI at the Verilator top level**.

The necessary fields already exist:

- `rvfi_valid`
- `rvfi_order`
- `rvfi_pc_rdata`
- `rvfi_insn`
- `rvfi_rd_addr`
- `rvfi_rd_wdata`
- optional debug aids:
  - `rvfi_trap`
  - `rvfi_mem_addr`
  - `rvfi_mem_rmask`
  - `rvfi_mem_wmask`
  - `rvfi_mem_rdata`
  - `rvfi_mem_wdata`

Recommended implementation choice:

- add a Verilator harness option such as `--commit-log <path>`
- on every `rvfi_valid`, emit one text line per committed instruction

Representative raw log format:

```text
CMT order=00000123 pc=8000004c insn=300f9073 rd=00 wdata=00000000 trap=0
CMT order=00000124 pc=80000050 insn=00004f81 rd=31 wdata=00000000 trap=0
```

This is still non-intrusive to `riscv-dv`. It only adds an RTL-side log emitter.

### 2.5 RTL Side: Zero-Change Fallback

If you do not want to touch the harness immediately, use the existing waveform support:

```bash
./sim_verilator/build/cl1_verilator --trace case.fst test.elf
```

Then parse `rvfi_*` top-level signals from the FST/VCD offline.

This fallback is slower, but it proves the design is not blocked on simulator log-format work.

### 2.6 External Parsing Strategy

Use an external converter, for example `rtl_trace_to_csv.py`, with this pipeline:

1. Read the raw RTL commit log or parse the waveform.
2. Keep only cycles where `rvfi_valid == 1`.
3. Extract:
   - `pc = rvfi_pc_rdata`
   - `binary = rvfi_insn`
   - `rd = rvfi_rd_addr`
   - `rd_wdata = rvfi_rd_wdata`
4. Convert `rd` from register number to ABI name to match Spike CSV.
5. Emit the standard `riscv-dv` CSV fields.

Normalization rules:

| Field | RTL source | Output rule |
| --- | --- | --- |
| `pc` | `rvfi_pc_rdata` | 8 hex digits, lower-case, no `0x` |
| `binary` | `rvfi_insn` | 8 hex digits, lower-case |
| `gpr` | `rvfi_rd_addr`, `rvfi_rd_wdata` | empty if `rd==0`; otherwise `abi_name:value` |
| `mode` | constant | `"3"` for M-mode |
| `instr` | optional | fill from offline disassembly lookup if available |
| `instr_str` | optional | fill from `objdump -d` indexed by PC |
| `operand` | optional | fill from `objdump -d` indexed by PC |

Important formatting detail:

- The Spike CSV uses ABI names such as `sp`, `t0`, `a5`.
- The RTL converter must use the same names or the stock comparator will flag false mismatches.

### 2.7 Recommended Comparator Reuse

Once both sides are in the same CSV schema, use the existing `riscv-dv` comparator unchanged:

```bash
python3 ../riscv-dv/scripts/instr_trace_compare.py \
  --csv_file_1 spike.csv \
  --csv_file_2 rtl.csv \
  --log compare.log
```

Why reuse it instead of writing a fresh comparator?

- it is already consistent with the local Spike CSV format
- it already compares in-order GPR architectural updates
- it keeps the new framework outside `riscv-dv`, but still compatible with it

### 2.8 Practical Alignment Rules

Use these rules to avoid false mismatches:

1. Compare the **same ELF** on both Spike and RTL whenever possible.
2. If Verilator uses `BIN/HEX`, still pass `--symbol-elf test.elf`.
3. Keep a disassembly file `test.dis` per case.
4. Keep raw RTL commit logs even if you also emit normalized CSV.
5. Store `rvfi_order` in the raw RTL log even if the CSV does not use it directly.

The `rvfi_order` field becomes the anchor for waveform triage later.

## 3. Triage And Root Cause Analysis Strategy

### 3.1 Classification Buckets

Every failing case should first be placed into one of three buckets:

| Bucket | Meaning | Typical owner |
| --- | --- | --- |
| `INFRA` | build, linker, memory-map, or simulator harness problem | verification infrastructure |
| `INVALID` | the generated test is outside the supported DUT/platform contract | target/test configuration |
| `RTL` | the test is valid and the DUT behavior is wrong | RTL/design |

Do not start waveform debugging until `INFRA` and `INVALID` are ruled out.

### 3.2 First-Pass Decision Tree

1. Did the image build and load cleanly?
   - If no: `INFRA`
2. Did the simulator stop on timeout, unmapped fetch, or unmapped data access before useful execution?
   - If yes: usually `INFRA`
3. Does the disassembly contain instructions or CSRs outside the supported contract?
   - If yes: `INVALID`
4. Does the failure depend on a feature the standalone harness does not model?
   - If yes: `INVALID`
5. If the test is inside contract and the mismatch is architectural:
   - classify as `RTL`

### 3.3 First 10 Checks Before Calling It An RTL Bug

Run this checklist in order:

1. Confirm `_start` is at `0x80000000` in `test.dis` or `readelf -l`.
2. Confirm no loadable section other than the optional trap stub lands outside the modelled address space.
3. Confirm `tohost` exists if the case relies on the `tohost` stop path.
4. If using `BIN/HEX`, confirm the simulator got `--symbol-elf test.elf`.
5. Confirm the same `test.elf` was used for Spike and RTL.
6. Confirm the test case is truly `RV32IMC` and not drifting into `A/F/D/V/S` space.
7. Confirm the test does not require interrupts unless the harness injects them.
8. Confirm the test does not require access-fault machinery the harness does not model.
9. Confirm the failure occurs after the architectural test body starts, not in the startup/trap scaffold.
10. Confirm the mismatch is reproducible with the same seed and same ELF.

### 3.4 How To Identify Incompatible Test Cases

The following conditions should be treated as `INVALID` unless the DUT contract is intentionally expanded.

#### Unsupported instructions

Examples:

- `A/F/D/V` instructions
- `sfence.vma`
- `sret`
- supervisor/user-mode-only instructions

Detection method:

- inspect `test.dis`
- grep the Spike CSV or disassembly for opcodes outside `RV32IMC`

#### Unsupported CSR accesses

The current RTL CSR block only exposes a small subset, including:

- `misa`
- `mstatus`
- `mtvec`
- `mscratch`
- `mepc`
- `mcause`
- `mip`
- `mie`
- debug CSRs

It does **not** visibly implement:

- `mtval`
- `medeleg`
- `mideleg`
- `sstatus`
- `scause`
- `satp`
- other S/U-mode CSRs

So tests that assume those CSRs are architecturally present are invalid for the current contract.

Important repo-specific caution:

- the RTL `misa` value currently does not advertise `M`, even though this verification plan assumes `RV32IMC`
- the local `riscv-dv` target currently lists `MTVAL` as implemented
- the generated exception handler in the sample tests reads `mtval`

Until that contract mismatch is resolved, any failure around `mtval` access must be treated carefully. It is either:

- a test-target mis-model
- or a real RTL/spec-compliance issue

It is **not** enough to call it a generic datapath bug.

#### Unsupported exception classes in the current core/harness

The standalone harness does not currently model:

- access faults from bus/PMA/PMP
- external interrupt sources
- timer interrupt sources
- software interrupt sources

And the RTL sources suggest incomplete support for:

- illegal-instruction exception plumbing
- misaligned load/store exceptions

Therefore these cases must be checked against the actual DUT contract before being promoted to RTL defects.

#### Misaligned accesses to non-existent or unmodelled regions

If a case touches addresses outside:

- `0x80000000 .. 0x80ffffff` RAM
- explicitly loaded sparse segments such as `0x20000000`
- the small MMIO set modelled by the harness

then the failure is a board/linker/test validity issue, not a core execution bug.

#### Trap tests invalidated by generator-side handler assumptions

The local `riscv-dv` analysis already notes that the default illegal-instruction handler increments `mepc` by `4`.

That makes some 16-bit trap-return checks invalid for this flow, especially:

- `C.EBREAK`
- 16-bit illegal instruction return precision

Those cases should be quarantined as `INVALID` for this specific flow until the test intent is made precise externally.

#### Interrupt-ready tests without a real interrupt source

The standalone Verilator harness currently drives:

- `io_ext_irq = 0`
- `io_sft_irq = 0`
- `io_tmr_irq = 0`

So interrupt generation tests are invalid unless the harness is extended to inject them deterministically.

### 3.5 When A Failure Should Be Treated As An RTL Bug

Once the previous checks pass, classify as `RTL` if:

- the ELF is valid for the DUT contract
- Spike and RTL execute the same image
- the test stays inside modelled address space
- no unsupported CSR or privilege feature is required
- the mismatch is reproducible

Examples of strong RTL-bug signatures:

- same PC and instruction, wrong destination value
- branch target or branch-taken decision diverges
- `mepc`, `mcause`, or `mtvec` behavior diverges on a valid trap
- load/store result differs for an aligned access in valid memory
- a valid `RV32IMC` instruction is treated as illegal
- writeback is lost, duplicated, or written to the wrong register

### 3.6 Backtracking From The Mismatch Point

Use the failing compare index as the pivot.

Recommended procedure:

1. Find the first mismatching architectural update in `compare.log`.
2. Pull a window of at least:
   - 10 commits before the mismatch
   - the mismatching commit
   - 5 commits after it if execution continues
3. Align:
   - Spike CSV
   - RTL CSV
   - raw RTL commit log
   - disassembly
4. Record the RTL `rvfi_order` for the mismatching commit.
5. Open the waveform at that commit window.

### 3.7 Waveform Debug Decision Guide

Use the mismatch shape to pick the first module to inspect.

| Symptom | First place to look |
| --- | --- |
| wrong `rd_wdata`, same PC | ALU/MDU/WB path |
| wrong next PC after branch/jump | `Cl1IDEXStage`, `Cl1IFStage` |
| wrong result after load/store | `Cl1LSU`, bus request/response, sign extension |
| wrong trap PC/cause | `Cl1EXCP`, `Cl1CSR`, `Cl1DbgCtrl` |
| wrong compressed-instruction behavior | `Cl1RVCExpander`, fetch alignment, PC increment |
| timeout/deadlock after memory activity | LSU handshake, flush/stall interaction |

Recommended per-commit signal set:

- `rvfi_pc_rdata`
- `rvfi_pc_wdata`
- `rvfi_insn`
- `rvfi_rd_addr`
- `rvfi_rd_wdata`
- `rvfi_trap`
- `rvfi_mem_addr`
- `rvfi_mem_rmask`
- `rvfi_mem_wmask`
- `rvfi_mem_rdata`
- `rvfi_mem_wdata`

Then go one level deeper:

- branch issues:
  - `branch_real_taken`
  - `branch_mis_prdt`
  - `flush_pc`
  - `flush_pc_ofst`
- LSU issues:
  - `io.out.req.bits.addr`
  - `io.out.req.bits.mask`
  - `io.out.req.bits.data`
  - `req_buf.memType`
  - `io.in.resp.bits.rdata`
- CSR/trap issues:
  - `csr.io.rdAddr`
  - `csr.io.wrAddr`
  - `csr.io.rdValue`
  - `csr.io.wrValue`
  - `cmt_epc_n`
  - `cmt_cause_n`
  - `flush_pc`
- WB issues:
  - `wb_commit`
  - `wen`
  - `rd_idx`
  - `wdata`

### 3.8 Strong Repo-Specific Suspect Areas

These are the first places to look for valid-test failures in the current workspace:

1. `Cl1CSR.scala`
   - missing or non-trapping absent CSR behavior
   - `misa` value consistency with the claimed `RV32IMC` contract
2. `Cl1LSU.scala`
   - sign extension and mask handling
   - lack of misaligned exception support
3. `Cl1EXCP.scala`
   - trap cause generation
   - `mepc/mcause/mtvec` sequencing
4. `Cl1IDEXStage.scala`
   - branch resolution
   - CSR read/write datapath
5. `Cl1WBStage.scala`
   - commit gating
   - interaction with flush and memory response timing

### 3.9 Evidence Package To Preserve Per Failure

For every failing seed, archive:

- the generated `.S`
- `test.elf`
- `test.map`
- `test.dis`
- `spike.log`
- `spike.csv`
- raw RTL log
- `rtl.csv`
- `compare.log`
- `wave.fst`
- exact command lines
- seed and test name

That evidence package is what lets you later distinguish:

- a reproducible DUT bug
- a flaky infrastructure issue
- a test outside contract

## Final Recommendation

For the current CL1 workspace, the most robust first implementation is:

1. run `riscv-dv` with `--steps gen`
2. externally link the emitted `.S` with a CL1-owned linker script
3. keep the generated `tohost` protocol for termination
4. run Spike on the same ELF
5. emit an RTL commit log from RVFI
6. convert both sides to `riscv-dv` CSV and reuse the stock comparator

Then add the wrapper BSP path only when you need stronger control over:

- early boot
- trap entry ownership
- uniform `ecall -> host-exit MMIO` behavior

That sequencing minimizes risk, respects the no-touch rule for `riscv-dv`, and gives a clean path from bring-up to root-cause triage.
