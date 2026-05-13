#ifndef SIM_VERILATOR_SELFTEST_COMMON_H_
#define SIM_VERILATOR_SELFTEST_COMMON_H_

#include <stdint.h>

#define DEFAULT_UART_ADDR 0x10000000u
#define DEFAULT_HOST_EXIT_ADDR 0x10000004u

static inline void mmio_write32(uint32_t addr, uint32_t value) {
  *(volatile uint32_t *)addr = value;
}

static inline void uart_putc_at(uint32_t uart_addr, char ch) {
  *(volatile uint8_t *)uart_addr = (uint8_t)ch;
}

static inline void uart_puts_at(uint32_t uart_addr, const char *str) {
  while (*str != '\0') {
    uart_putc_at(uart_addr, *str++);
  }
}

static inline void sim_finish_host_exit_at(uint32_t host_exit_addr, uint32_t code) {
  mmio_write32(host_exit_addr, code);
  while (1) {
  }
}

static inline void sim_finish_host_exit(uint32_t code) {
  sim_finish_host_exit_at(DEFAULT_HOST_EXIT_ADDR, code);
}

#endif
