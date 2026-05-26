.section .text
.globl main

#include "address_map.h"

main:
  la t0, illegal_handler
  csrw mtvec, t0

illegal_insn:
  .word 0xffffffff

after_illegal:
illegal_c_insn:
  .2byte 0x0000

after_illegal_c:
illegal_csr:
  csrr t0, 0x345

after_illegal_csr:
  li a0, 0x10
  ret

illegal_handler:
  csrr t0, mcause
  li t1, 2
  bne t0, t1, fail

  csrr t0, mepc
  la t1, illegal_insn
  beq t0, t1, handle_illegal
  la t1, illegal_c_insn
  beq t0, t1, handle_illegal_c
  la t1, illegal_csr
  beq t0, t1, pass
  j fail

handle_illegal:
  la t1, after_illegal
  csrw mepc, t1
  mret

handle_illegal_c:
  la t1, after_illegal_c
  csrw mepc, t1
  mret

pass:
  li t0, CL1_DEFAULT_HOST_EXIT_ADDR
  sw zero, 0(t0)

pass_loop:
  j pass_loop

fail:
  li t0, CL1_DEFAULT_HOST_EXIT_ADDR
  li t1, 0x22
  sw t1, 0(t0)

fail_loop:
  j fail_loop
