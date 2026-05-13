#include "common.h"

int main(void) {
  uart_puts_at(DEFAULT_UART_ADDR, "HOST_EXIT_FAIL\n");
  sim_finish_host_exit(2);
  return 2;
}
