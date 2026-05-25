#ifndef SIM_VERILATOR_OPTIONS_H_
#define SIM_VERILATOR_OPTIONS_H_

#include <optional>
#include <string>
#include <vector>

#include "common.h"
#include "random_irq.h"

namespace cl1sim {

inline constexpr uint32_t kCl1DefaultRamBase = 0x80000000u;
inline constexpr uint64_t kCl1DefaultRamSize = 16ull * 1024ull * 1024ull;
inline constexpr uint32_t kCl1DefaultLoadAddr = 0x80000000u;
inline constexpr bool kCl1HasDefaultUart = true;
inline constexpr uint32_t kCl1DefaultUartAddr = 0x10000000u;
inline constexpr bool kCl1HasDefaultHostExit = true;
inline constexpr uint32_t kCl1DefaultHostExitAddr = 0x10000004u;

struct Options {
  fs::path image_path;
  std::optional<fs::path> symbol_elf_path;
  std::optional<fs::path> trace_path;
  std::optional<fs::path> commit_log_path;
  uint32_t ram_base = kCl1DefaultRamBase;
  uint64_t ram_size = kCl1DefaultRamSize;
  uint32_t load_addr = kCl1DefaultLoadAddr;
  bool has_uart = kCl1HasDefaultUart;
  uint32_t uart_addr = kCl1DefaultUartAddr;
  bool has_host_exit = kCl1HasDefaultHostExit;
  uint32_t host_exit_addr = kCl1DefaultHostExitAddr;
  uint64_t max_cycles = kDefaultMaxCycles;
  std::vector<AddressRegion> extra_regions;
  RandomIrqConfig random_irq;
  std::string image_type = "auto";
  GuestOutputMode guest_output = GuestOutputMode::kTagged;
  bool quiet = false;
  bool verbose = false;
};

IrqSignals parse_irq_lines_arg(const std::string& text);
AddressRegion parse_address_region_arg(const std::string& text, std::size_t index);
std::vector<AddressRegion> build_address_regions(const Options& options);
void print_usage(const char* argv0);
Options parse_args(int argc, char** argv);

}  // namespace cl1sim

#endif  // SIM_VERILATOR_OPTIONS_H_
