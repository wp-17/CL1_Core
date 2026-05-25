.section .text
.globl main

#include "address_map.h"

.equ HOST_EXIT_ADDR, CL1_DEFAULT_HOST_EXIT_ADDR
.equ MCAUSE_BREAKPOINT, 3

main:
  la t0, trap_handler
  csrw mtvec, t0

breakpoint_insn:
  ebreak

after_breakpoint:
  li t0, HOST_EXIT_ADDR
  sw zero, 0(t0)

pass_loop:
  j pass_loop

.align 2
trap_handler:
  csrr t0, mcause
  li t1, MCAUSE_BREAKPOINT
  bne t0, t1, fail

  csrr t0, mepc
  la t1, breakpoint_insn
  bne t0, t1, fail

  la t0, after_breakpoint
  csrw mepc, t0
  mret

fail:
  li t0, HOST_EXIT_ADDR
  li t1, 0x31
  sw t1, 0(t0)

fail_loop:
  j fail_loop
