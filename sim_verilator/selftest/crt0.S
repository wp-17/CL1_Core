.section .text.init
.globl _start

#include "address_map.h"

_start:
  la sp, _stack_top

  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b

2:
  call main

  li t0, CL1_DEFAULT_HOST_EXIT_ADDR
  sw a0, 0(t0)

3:
  j 3b
