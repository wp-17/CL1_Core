#include "common.h"

int main(void) {
  uart_puts_at(DEFAULT_UART_ADDR, "EBREAK_PASS\n");
  __asm__ volatile(
      "mv a0, zero\n"
      "ebreak\n"
      :
      :
      : "a0", "memory");

  sim_finish_host_exit(3);
  return 3;
}
