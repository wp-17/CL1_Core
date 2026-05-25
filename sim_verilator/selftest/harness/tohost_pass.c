#include "common.h"

volatile uint32_t tohost __attribute__((section(".tohost")));
volatile uint32_t fromhost __attribute__((section(".fromhost")));

int main(void) {
  uart_puts_at(DEFAULT_UART_ADDR, "TOHOST_PASS\n");
  fromhost = 0;
  tohost = 1;
  while (1) {
  }
}
