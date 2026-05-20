# RV32IMC_Zicsr M-Mode Exception/Interrupt Support Tables

Scope: `rv32imc_mmode_trap`, RV32IMC_Zicsr, M-mode only. Floating-point
exceptions, page faults, U/S-mode exceptions, optional debug/trigger traps, and
non-standard platform-local interrupts are intentionally omitted. Standard
machine interrupt rows are included because they are part of the machine trap
surface, but they remain blocked in the current project state unless a
platform interrupt source model is added. DUT results below are from the CL1
Verilator framework run with Spike trace comparison.

Revision basis: current tests in
`yaml/rv32imc_mmode_exception_testpoints_testlist.yaml` and
`directed_asm_tests/rv32imc_mmode_trap/*.S`. The current testlist contains 35
entries. The available Spike artifact under
`verification_output/rv32imc_mmode_exception_testpoints` contains 34 testcase
directories without reported `ERROR`, `FAILED`, timeout, or self-check
`tohost=2` failures; that artifact does not include
`rv32imc_mmode_directed_required_csr_access`, which is present in the current
testlist and source tree. During this update, `required_csr_access` was compiled
and run separately with Spike on 2026-04-29 under
`verification_output/rv32imc_mmode_required_csr_access_check`, and `run.py`
returned 0.

DUT result basis: on 2026-05-14, `sim_verilator/run_riscv_dv.py` ran
`verification_output/rv32imc_mmode_directed_suite` with 11/11 RUN-PASS and
11/11 CMP-PASS, `verification_output/rv32imc_mmode_exception_testpoints` with
34/34 RUN-PASS and 34/34 CMP-PASS, and
`verification_output/rv32imc_mmode_required_csr_access_check` with 1/1 RUN-PASS
and 1/1 CMP-PASS. The generated load/store stress artifact
`verification_output/rv32imc_mmode_closure_loadstore_round3` was also run with
8/8 RUN-PASS and 8/8 CMP-PASS. All four CL1 runs used
`--no-build --compare --max-cycles 200000`; generated artifacts are under
`sim_verilator/build/riscv_dv/`.

The revised `absent_csr` exhaustive CSR-address sweep is present in the
exception-testpoints artifact and the directed/high-coverage artifacts. The
`c_illegal` directed test has been expanded from the single all-zero halfword to
an exhaustive RV32IC reserved/illegal 16-bit encoding sweep. It compiled and ran
through the exception-testpoints, directed-suite, and directed-suite-multi YAML
entries with the default ISS timeout, and Spike observed 20,329
illegal-instruction traps before the test wrote self-check `tohost=1`.

Current target metadata:

- `supported_interrupt_mode = [DIRECT]`, `max_interrupt_vector_num = 0`.
- `support_pmp = 0`, `support_unaligned_load_store = 0`.
- `implemented_interrupt = []`.
- `implemented_exception = [ILLEGAL_INSTRUCTION, BREAKPOINT,
  LOAD_ADDRESS_MISALIGNED, STORE_AMO_ADDRESS_MISALIGNED, ECALL_MMODE]`.

Status terms:

- `Supported`: a self-checking directed test or constrained generated-flow
  entry exists for the listed test point.
- `Partial`: coverage exists but is intentionally narrow, policy-sensitive, or
  not a complete matrix closure.
- `Needs Dev`: generator or test implementation work is still required.
- `Blocked`: the test requires a platform resource or DUT contract not present
  in the current target metadata.
- `Out of Scope`: outside the fixed RV32IMC_Zicsr M-mode-only profile.
- `PASS`: the CL1 Verilator run completed successfully and the generated RTL
  trace matched the corresponding Spike trace for that runnable test.
- `Not run`: no current runnable artifact was executed for that row.

## Instruction Address Misaligned, `mcause=0`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| Taken branch/JAL target at halfword boundary | Supported | 1 directed | Yes, no trap with `C` | PASS | `rv32imc_mmode_directed_ialign16_halfword_targets.S`; trap vector fails on any unexpected trap and checks target `addr[1]=1`. |
| `JALR` computed target with bit 0 set | Supported | 1 directed | Yes, bit 0 cleared | PASS | `rv32imc_mmode_directed_jalr_bit0_clear.S`; verifies normal return after bit-0 clearing. |
| Compressed jump/control smoke | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_compressed_jump.S`; covers compressed jump/control flow without cause-0 trap. |
| `misa.C` disabled IALIGN=32 behavior | Out of Scope | 0 | Yes if `C` disabled | Not run | Outside fixed `rv32imc` target unless DUT supports writable `misa.C` and a defined C-disable flow. |

## Instruction Access Fault, `mcause=1`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| Fetch from PMA execute-deny region | Blocked | 0 | Needs platform model | Blocked | No PMA map or execute-deny fetch resource is modeled. |
| Fetch from PMP X-deny region | Blocked | 0 | Needs PMP setup | Blocked | `support_pmp=0`; PMP CSRs/regions are not available in target metadata. |
| Fault reported on target instruction after jump/branch | Blocked | 0 | Possible with platform model | Blocked | Straightforward only after a real fetch-fault source exists; `gen_instr_fault_handler()` is still TODO. |

## Illegal Instruction, `mcause=2`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| RV32IC 16-bit reserved/illegal compressed encodings, including all-zero | Supported | 1 directed / 20,329 encodings | Yes | PASS | `rv32imc_mmode_directed_c_illegal.S`; assembler-time sweep emits every RV32IC halfword expected to raise illegal-instruction in RV32IMC_Zicsr and excludes legal, hint, breakpoint, and non-compressed-prefix encodings. Handler checks precise ordered `mepc` and advances by 2. |
| 32-bit all-zero illegal encoding | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_illegal_word.S`; handler advances `mepc` by 4. |
| Reserved/custom 32-bit word with optional `mtval` | Partial | 1 directed | Encoding-dependent | PASS | `rv32imc_mmode_directed_mtval_illegal_word.S`; allows `mtval` to be zero or the trapped instruction bits. Arbitrary reserved-opcode behavior remains policy-sensitive. |
| All-ones 32-bit illegal encoding | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_all_ones_illegal.S`; checks precise `mepc`, optional `mtval`, and single-trap recovery. |
| Absent `A` extension AMO encoding | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_amo_illegal.S`; AMO is illegal instruction, not store/AMO exception, in RV32IMC. |
| `SFENCE.VMA` without S/V support | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_sfence_vma_illegal.S`; privileged illegal-instruction path. |
| `SRET` with no S-mode | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_sret_illegal.S`; M-only target checks cause 2 and precise `mepc`. |
| Non-existent / absent CSR access | Supported | 1 directed | Yes, with optional CSR windows tolerated | PASS | `rv32imc_mmode_directed_absent_csr.S`; exhausts the full 12-bit CSR address space. Required implemented CSRs must not trap; required absent/inaccessible CSRs must raise illegal-instruction; optional/policy-sensitive windows (`mcountinhibit`, HPM, PMP, trigger, and Zicntr aliases) are accepted either as implemented reads or illegal traps. |
| Write to required read-only CSRs | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_ro_csr_write.S`; covers `mvendorid`, `marchid`, `mimpid`, `mhartid`, and `mconfigptr` across register and immediate write forms. |
| Debug-only CSR range `0x7B0-0x7BF` | Supported | 1 directed | Expected | PASS | `rv32imc_mmode_directed_debug_csr_illegal.S`; enumerates the full debug-only CSR window outside Debug Mode. |
| CSR no-write forms to required read-only CSRs | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_ro_csr_nowrite.S`; `CSRRS/CSRRC rs1=x0` and immediate `uimm=0` forms must not trap solely due to RO CSR. |
| Required CSR legal access | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_required_csr_access.S`; reads every required CSR and writes back current values to required RW/WARL CSRs. Absent from the archived 34-testcase Spike artifact, then separately verified with Spike and RTL compare in `verification_output/rv32imc_mmode_required_csr_access_check`. |
| Unsupported S interrupt bits read as zero | Partial | 1 directed | Yes | PASS | `rv32imc_mmode_directed_mie_mip_sbits.S`; negative CSR behavior for S-level interrupt bits, not an exception. |
| Unsupported WLRL/WARL value writes | Needs Dev | 0 directed | Policy-dependent | Needs Dev | CSR/bit-field expectations are documented in `rv32imc_zicsr_mmode_required_csrs.md`; current tests do not yet write unsupported values and check legalize/no-trap readback. This is not a mandatory cause-2 trap point unless the DUT explicitly defines trapping behavior. Candidate checks include `mstatus.MPP`, RO0 `mstatus/mstatush` fields, unsupported `misa` extension bits, `mtvec.MODE`/alignment coercion, `mepc[0]`, and unsupported `mcause` codes. |
| Generated random illegal `.4byte` injection | Partial | 1 generated-flow entry | Yes | PASS | `rv32imc_mmode_generated_illegal_4byte_injection`; generated handler advances `mepc` by 4 and is suitable only for 4-byte injected slots. |
| `WFI` legal no-trap behavior | Blocked | 0 | Simulator/platform-dependent | Blocked | Portable self-check can hang unless a wake source or NOP-like simulator rule is guaranteed. |

## Breakpoint, `mcause=3`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| 32-bit `EBREAK` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_ebreak.S`; basic cause check exists. |
| 16-bit `C.EBREAK` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_c_ebreak.S`; handler advances `mepc` by 2. |
| `C.EBREAK` at halfword-only PC | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_mepc_16bit.S`; checks `mepc[1]` preservation. |
| Trap-entry `mstatus` and `mret` stacking | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_mstatus_mret_stack.S`; checks basic `MIE/MPIE/MPP` trap entry and `mret` restore behavior. |
| Generated common `EBREAK` handler | Needs Dev | 0 | N/A | Needs Dev | Generator exception dispatch still lacks cause-3 routing and `gen_ebreak_handler()` is TODO. |
| Trigger/debug breakpoint or watchpoint | Blocked | 0 | Platform/debug dependent | Blocked | Optional debug/trigger environment is not modeled. |

## Load Address Misaligned, `mcause=4`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| Misaligned `LW` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_unaligned_lw.S`; one word-misaligned offset covered. |
| Misaligned `LH` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_unaligned_lh.S`; includes optional `mtval` address check. |
| Misaligned `LHU` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_unaligned_lhu.S`; includes optional `mtval` address check. |
| Misaligned `C.LW` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_c_unaligned_lw.S`; checks 16-bit trap return. |
| Misaligned `C.LWSP` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_c_unaligned_lwsp.S`; checks 16-bit trap return and optional `mtval`. |
| Byte loads no-trap control | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_load_no_trap_controls.S`; byte loads are naturally aligned and trap vector fails on any trap. |
| Aligned load no-trap controls | Supported | 1 directed | Yes | PASS | Same test covers naturally aligned `LH/LHU/LW/C.LW/C.LWSP` controls. |
| Random unaligned load stream | Partial | 8 generated load/store stress cases | Yes | PASS | `rv32imc_mmode_closure_loadstore_round3`; generated streams include misaligned `LH/LHU/LW` offsets and passed RTL/Spike compare. This is still not a full size/offset/compressed-form matrix closure. |
| Access-fault priority over misalignment | Blocked | 0 | Platform-dependent | Blocked | No PMA/PMP/MMIO fault region or DUT priority policy is modeled. |

## Load Access Fault, `mcause=5`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| PMA read-deny load | Blocked | 0 | Needs platform model | Blocked | No PMA read-deny region or platform map. |
| PMP read-deny load | Blocked | 0 | Needs PMP setup | Blocked | `support_pmp=0`; load access fault is not listed in target `implemented_exception`. |
| Side-effect/MMIO load fault | Blocked | 0 | Platform-dependent | Blocked | No MMIO faulting address, side-effect checker, or ISS model. |
| Misaligned load forced to access fault | Blocked | 0 | Platform-dependent | Blocked | Requires platform-defined priority and a faulting side-effect/PMA/PMP region. |
| Generated load fault handler | Needs Dev | 0 | N/A | Needs Dev | `gen_load_fault_handler()` is TODO. |

## Store/AMO Address Misaligned, `mcause=6`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| Misaligned `SW` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_unaligned_sw.S`; one word-misaligned offset covered. |
| Misaligned `SH` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_unaligned_sh.S`; includes optional `mtval` address check. |
| Misaligned `C.SW` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_c_unaligned_sw.S`; checks 16-bit trap return. |
| Misaligned `C.SWSP` | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_c_unaligned_swsp.S`; checks 16-bit trap return and optional `mtval`. |
| Byte stores no-trap control | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_store_no_trap_controls.S`; byte stores are naturally aligned and trap vector fails on any trap. |
| Aligned store no-trap controls | Supported | 1 directed | Yes | PASS | Same test covers naturally aligned `SH/SW/C.SW/C.SWSP` controls. |
| Random unaligned store stream | Partial | 8 generated load/store stress cases | Yes | PASS | `rv32imc_mmode_closure_loadstore_round3`; generated streams include misaligned `SH/SW` offsets and passed RTL/Spike compare. This is still not a full size/offset/compressed-form matrix closure. |
| AMO address-misaligned behavior | Out of Scope | 0 | N/A | Not run | `A` is absent; AMO encodings are covered as illegal-instruction tests. |
| Access-fault priority over misalignment | Blocked | 0 | Platform-dependent | Blocked | No PMA/PMP/MMIO fault region or DUT priority policy is modeled. |

## Store/AMO Access Fault, `mcause=7`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| PMA write-deny store | Blocked | 0 | Needs platform model | Blocked | No PMA/MMIO write-deny region or platform map. |
| PMP write-deny store | Blocked | 0 | Needs PMP setup | Blocked | `support_pmp=0`; PMP CSRs are unavailable. |
| Side-effect/MMIO store fault | Blocked | 0 | Platform-dependent | Blocked | No MMIO faulting resource or side-effect checker. |
| Misaligned store forced to access fault | Blocked | 0 | Platform-dependent | Blocked | Requires priority policy and a faulting region. |
| Partial visible store side effects | Blocked | 0 | Platform-dependent | Blocked | Requires a platform side-effect observability contract. |
| AMO access fault | Out of Scope | 0 | N/A | Not run | `A` is absent from RV32IMC_Zicsr. |
| Generated store fault handler | Needs Dev | 0 | N/A | Needs Dev | `gen_store_fault_handler()` is TODO and generated dispatch does not route cause 7. |

## Environment Call From M-mode, `mcause=11`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| `ECALL` executed in M-mode | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_ecall.S`; cause 11 check exists. |
| Generated test-done `ECALL` path | Supported | Generated flow | Yes | PASS | Used for generated-test termination; not by itself a full trap-state test. |
| `mepc` equals ECALL instruction PC | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_ecall_mepc_side_effect.S`; checks precise ECALL `mepc`. |
| ECALL does not retire / no side effect | Supported | 1 directed | Yes | PASS | Same test checks pre/post markers and guarded side effects around the ECALL site. |

## Standard Machine Interrupts and `mtvec`

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| Machine software interrupt, MSI, `mcause=0x80000003` | Blocked | 0 | Needs platform source | Blocked | Current target has `implemented_interrupt=[]` and no CLINT/MSIP or equivalent memory-mapped software interrupt source. |
| Machine timer interrupt, MTI, `mcause=0x80000007` | Blocked | 0 | Needs platform source | Blocked | No `mtime`/`mtimecmp` map, tick model, or MTIP pending-source stimulus is modeled. |
| Machine external interrupt, MEI, `mcause=0x8000000b` | Blocked | 0 | Needs platform source | Blocked | No PLIC/external interrupt controller map or source assertion mechanism is modeled. |
| Standard interrupt take gates: `mstatus.MIE`, `mie`, and `mip` | Blocked | 0 | Needs pending source | Blocked | `rv32imc_mmode_directed_mie_mip_sbits.S` checks unsupported S-level bits read as zero, but it does not create a real M-level pending interrupt. |
| Standard interrupt priority, MEI > MSI > MTI | Blocked | 0 | Needs simultaneous sources | Blocked | Requires all three M-level sources to be implemented and asserted together in a platform model. |
| Generated M-mode interrupt handler/CSR setup | Partial | 1 generated-flow entry outside exception table | Spike can generate/compile/run only setup | Not run | `rv32imc_interrupt_ready` in `yaml/task2_exception_interrupt_testlist.yaml` enables interrupt-related generator paths, but cannot assert MSIP/MTIP/MEIP. |
| `mtvec` direct mode for synchronous exceptions | Supported | Covered by directed trap tests | Yes | PASS | Directed exception tests program `mtvec` and reach the direct trap handler on expected synchronous traps. |
| `mtvec` vectored interrupt dispatch | Out of Scope for current target | 0 | Yes if enabled | Not run | `supported_interrupt_mode` is direct-only and `max_interrupt_vector_num=0`; vectored interrupt behavior requires target metadata and platform interrupt sources. |

## No-Exception Negative Controls

| Specific Trigger / Test Point | riscv-dv Support Status | Test Case Count | Spike Support | DUT Status | Notes |
|---|---|---:|---|---|---|
| Integer divide by zero | Supported | 1 directed | Yes | PASS | `rv32imc_mmode_directed_div_no_trap_controls.S`; trap vector fails on any trap and checks `DIV/DIVU/REM/REMU` architectural results. |
| Signed divide overflow | Supported | 1 directed | Yes | PASS | Same test checks `INT_MIN / -1` and `INT_MIN % -1` result semantics. |
| `WFI` in M-mode | Blocked | 0 | Simulator/platform-dependent | Blocked | Needs a wake source or a guaranteed NOP-like simulator rule before a portable no-trap test can be self-checking. |
| RV32IC halfword instruction targets | Supported | 2 directed | Yes | PASS | `rv32imc_mmode_directed_ialign16_halfword_targets.S` and `rv32imc_mmode_directed_compressed_jump.S` cover no-cause-0 halfword-target behavior. |
