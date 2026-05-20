.section .text
.globl main

.equ HOST_EXIT_ADDR, 0x10000004
.equ MSTATUS_MIE,   0x00000008
.equ MIE_MEIE,      0x00000800
.equ MCAUSE_MEI,    0x8000000b

main:
  la t0, interrupt_handler
  csrw mtvec, t0
  li t0, MIE_MEIE
  csrw mie, t0
  li t0, MSTATUS_MIE
  csrw mstatus, t0

wait_irq:
  addi s0, s0, 1
  j wait_irq

.align 2
interrupt_handler:
  csrr t0, mcause
  li t1, MCAUSE_MEI
  bne t0, t1, fail

  csrr t0, mepc
  addi t0, t0, 4
  csrw mepc, t0
  li t0, HOST_EXIT_ADDR
  sw zero, 0(t0)

pass_loop:
  j pass_loop

fail:
  li t0, HOST_EXIT_ADDR
  li t1, 0x61
  sw t1, 0(t0)

fail_loop:
  j fail_loop
