.section .text
.globl main

.equ TEST_REGION_ADDR, 0x60000000

main:
  li t0, TEST_REGION_ADDR
  li t1, 0x5a
  sw t1, 0(t0)
  lw t2, 0(t0)
  bne t1, t2, fail
  li a0, 0
  ret

fail:
  li a0, 0x41
  ret
