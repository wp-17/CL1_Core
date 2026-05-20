.section .text
.globl main

.equ HOST_EXIT_ADDR, 0x10000004
.equ ILLEGAL_ADDR,  0x60000000

main:
  la t0, trap_handler
  csrw mtvec, t0
  la s0, test_table
  li s1, 0

run_next:
  lw t0, 0(s0)
  beqz t0, pass
  lw t1, 4(s0)
  lw t2, 8(s0)
  lw t3, 12(s0)
  jr t0

load_word_fault:
  lw a1, 0(t1)
load_word_after:
  j run_next

load_byte_fault:
  lb a1, 0(t1)
load_byte_after:
  j run_next

store_word_fault:
  sw zero, 0(t1)
store_word_after:
  j run_next

store_byte_fault:
  sb zero, 0(t1)
store_byte_after:
  j run_next

.align 2
trap_handler:
  csrr t4, mcause
  bne t4, t2, fail
  csrr t4, mepc
  bne t4, t0, fail
  csrr t4, mtval
  bnez t4, check_mtval

advance_case:
  csrw mepc, t3
  addi s0, s0, 16
  addi s1, s1, 1
  mret

check_mtval:
  bne t4, t1, fail
  j advance_case

pass:
  li t0, 4
  bne s1, t0, fail
  li t0, HOST_EXIT_ADDR
  sw zero, 0(t0)

pass_loop:
  j pass_loop

fail:
  li t0, HOST_EXIT_ADDR
  li t1, 0x35
  sw t1, 0(t0)

fail_loop:
  j fail_loop

.section .rodata
.align 2
test_table:
  .word load_word_fault,  ILLEGAL_ADDR + 0, 5, load_word_after
  .word load_byte_fault,  ILLEGAL_ADDR + 1, 5, load_byte_after
  .word store_word_fault, ILLEGAL_ADDR + 0, 7, store_word_after
  .word store_byte_fault, ILLEGAL_ADDR + 2, 7, store_byte_after
  .word 0, 0, 0, 0
