#include "common.h"

#define CUSTOM_HOST_EXIT_ADDR 0x10000080u
#define CUSTOM_UART_ADDR 0x10000084u

int main(void) {
  uart_puts_at(CUSTOM_UART_ADDR, "CMMIO\n");
  sim_finish_host_exit_at(CUSTOM_HOST_EXIT_ADDR, 0);
  return 0;
}
