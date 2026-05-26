#include "options.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <iostream>
#include <stdexcept>

namespace cl1sim {

namespace {

bool looks_numeric_arg(const std::string& text) {
  return !text.empty() && std::isdigit(static_cast<unsigned char>(text.front()));
}

}  // namespace

IrqSignals parse_irq_lines_arg(const std::string& text) {
  IrqSignals lines;
  bool saw_line = false;
  const auto parts = split_colon_separated(text);
  for (const auto& raw_part : parts) {
    std::string part = trim(raw_part);
    std::transform(part.begin(), part.end(), part.begin(), [](unsigned char ch) {
      return static_cast<char>(std::tolower(ch));
    });
    if (part.empty() || part == "none") {
      continue;
    }
    saw_line = true;
    if (part == "all") {
      lines.ext = true;
      lines.sft = true;
      lines.tmr = true;
    } else if (part == "ext" || part == "external") {
      lines.ext = true;
    } else if (part == "sft" || part == "soft" || part == "software") {
      lines.sft = true;
    } else if (part == "tmr" || part == "timer") {
      lines.tmr = true;
    } else {
      throw std::runtime_error("bad --irq-lines `" + text + "`, expected ext/sft/tmr/all separated by ':'");
    }
  }
  if (!saw_line) {
    throw std::runtime_error("bad --irq-lines `" + text + "`, at least one interrupt line is required");
  }
  return lines;
}

AddressRegion parse_address_region_arg(const std::string& text, std::size_t index) {
  const auto parts = split_colon_separated(text);

  std::string name = "extra_region_" + std::to_string(index);
  std::string base_text;
  std::string size_text;
  std::string perms = "rwx";

  if (parts.size() == 2 || (parts.size() == 3 && looks_numeric_arg(parts[0]))) {
    base_text = parts[0];
    size_text = parts[1];
    if (parts.size() == 3) {
      perms = parts[2];
    }
  } else if (parts.size() == 3 || parts.size() == 4) {
    name = parts[0];
    base_text = parts[1];
    size_text = parts[2];
    if (parts.size() == 4) {
      perms = parts[3];
    }
  } else {
    throw std::runtime_error(
        "bad --region `" + text + "`, expected name:base:size[:rwx] or base:size[:rwx]");
  }

  name = trim(name);
  base_text = trim(base_text);
  size_text = trim(size_text);
  perms = trim(perms);
  if (name.empty() || base_text.empty() || size_text.empty()) {
    throw std::runtime_error("bad --region `" + text + "`, name/base/size must be non-empty");
  }

  AddressRegion region;
  region.name = name;
  region.base = static_cast<uint32_t>(parse_u64_arg(base_text));
  region.size = parse_u64_arg(size_text);
  if (region.size == 0) {
    throw std::runtime_error("bad --region `" + text + "`, size must be non-zero");
  }

  region.readable = false;
  region.writable = false;
  region.executable = false;
  if (perms.empty()) {
    perms = "rwx";
  }
  for (const unsigned char raw_ch : perms) {
    const char ch = static_cast<char>(std::tolower(raw_ch));
    if (ch == 'r') {
      region.readable = true;
    } else if (ch == 'w') {
      region.writable = true;
    } else if (ch == 'x') {
      region.executable = true;
    } else if (ch == '-') {
      continue;
    } else {
      throw std::runtime_error("bad --region `" + text + "`, permission must use r/w/x/-");
    }
  }
  return region;
}

std::vector<AddressRegion> build_address_regions(const Options& options) {
  std::vector<AddressRegion> regions;
  if (options.ram_size != 0) {
    regions.push_back(AddressRegion{
        "ram",
        options.ram_base,
        options.ram_size,
        true,
        true,
        true,
    });
  }
  if (options.has_uart) {
    regions.push_back(AddressRegion{
        "uart",
        align_word(options.uart_addr),
        kWordBytes,
        true,
        true,
        false,
    });
  }
  if (options.has_host_exit) {
    regions.push_back(AddressRegion{
        "host_exit",
        align_word(options.host_exit_addr),
        kWordBytes,
        true,
        true,
        false,
    });
  }
  regions.insert(regions.end(), options.extra_regions.begin(), options.extra_regions.end());
  return regions;
}

void print_usage(const char* argv0) {
  std::cerr
      << "Usage: " << argv0 << " [options] <image>\n"
      << "Options:\n"
      << "  --image-type <auto|elf|bin|hex>  Force image parser (default: auto)\n"
      << "  --symbol-elf <path>              Parse tohost/fromhost symbols from a sidecar ELF\n"
      << "  --load-addr <addr>               Base load address for BIN/HEX images (default: "
      << hex32(kCl1DefaultLoadAddr) << ")\n"
      << "  --ram-base <addr>                Simulated RAM base (default: "
      << hex32(kCl1DefaultRamBase) << ")\n"
      << "  --ram-size <bytes>               Simulated RAM size, supports K/M/G suffixes (default: "
      << kCl1DefaultRamSize << ")\n"
      << "  --host-exit-addr <addr>          MMIO exit register address (default: "
      << hex32(kCl1DefaultHostExitAddr) << ")\n"
      << "  --uart-addr <addr>               MMIO UART TX register address (default: "
      << hex32(kCl1DefaultUartAddr) << ")\n"
      << "  --region <name:base:size[:rwx]>  Add an allowed address region; repeatable\n"
      << "  --irq-lines <ext:sft:tmr|all>    Enable random interrupt inputs\n"
      << "  --irq-seed <value>               Seed for random interrupts (default: 1)\n"
      << "  --irq-delay <min:max>            Cycles between interrupt pulses (default: 8:64)\n"
      << "  --irq-width <min:max>            Interrupt pulse width in cycles (default: 1:4)\n"
      << "  --max-cycles <count>             Timeout in cycles (default: 1000000)\n"
      << "  --trace <path.fst>               Dump an FST waveform\n"
      << "  --commit-log <path>              Dump RVFI commit log lines\n"
      << "  --guest-output <tagged|raw>      Format guest UART output (default: tagged)\n"
      << "  --no-ebreak-stop                 Do not treat committed ebreak as simulator exit\n"
      << "  --quiet                          Suppress image metadata logs\n"
      << "  --verbose                        Print bus/RVFI activity for debug\n"
      << "  -h, --help                       Show this help\n";
}

Options parse_args(int argc, char** argv) {
  Options options;

  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    auto need_value = [&](const std::string& flag) -> std::string {
      if (i + 1 >= argc) {
        throw std::runtime_error("missing value for " + flag);
      }
      ++i;
      return argv[i];
    };

    if (arg == "-h" || arg == "--help") {
      print_usage(argv[0]);
      std::exit(0);
    } else if (arg == "--image-type") {
      options.image_type = need_value(arg);
    } else if (arg == "--symbol-elf") {
      options.symbol_elf_path = fs::path(need_value(arg));
    } else if (arg == "--load-addr") {
      options.load_addr = static_cast<uint32_t>(parse_u64_arg(need_value(arg)));
    } else if (arg == "--ram-base") {
      options.ram_base = static_cast<uint32_t>(parse_u64_arg(need_value(arg)));
    } else if (arg == "--ram-size") {
      options.ram_size = parse_u64_arg(need_value(arg));
    } else if (arg == "--host-exit-addr") {
      options.has_host_exit = true;
      options.host_exit_addr = static_cast<uint32_t>(parse_u64_arg(need_value(arg)));
    } else if (arg == "--uart-addr") {
      options.has_uart = true;
      options.uart_addr = static_cast<uint32_t>(parse_u64_arg(need_value(arg)));
    } else if (arg == "--region") {
      options.extra_regions.push_back(parse_address_region_arg(need_value(arg), options.extra_regions.size()));
    } else if (arg == "--irq-lines") {
      options.random_irq.enabled = parse_irq_lines_arg(need_value(arg));
    } else if (arg == "--irq-seed") {
      options.random_irq.seed = parse_u64_arg(need_value(arg));
    } else if (arg == "--irq-delay") {
      const auto parts = split_colon_separated(need_value(arg));
      if (parts.size() != 2) {
        throw std::runtime_error("bad --irq-delay, expected min:max");
      }
      options.random_irq.min_delay = parse_u64_arg(parts[0]);
      options.random_irq.max_delay = parse_u64_arg(parts[1]);
    } else if (arg == "--irq-width") {
      const auto parts = split_colon_separated(need_value(arg));
      if (parts.size() != 2) {
        throw std::runtime_error("bad --irq-width, expected min:max");
      }
      options.random_irq.min_width = parse_u64_arg(parts[0]);
      options.random_irq.max_width = parse_u64_arg(parts[1]);
    } else if (arg == "--max-cycles") {
      options.max_cycles = parse_u64_arg(need_value(arg));
    } else if (arg == "--trace") {
      options.trace_path = fs::path(need_value(arg));
    } else if (arg == "--commit-log") {
      options.commit_log_path = fs::path(need_value(arg));
    } else if (arg == "--guest-output") {
      const std::string mode = need_value(arg);
      if (mode == "tagged") {
        options.guest_output = GuestOutputMode::kTagged;
      } else if (mode == "raw") {
        options.guest_output = GuestOutputMode::kRaw;
      } else {
        throw std::runtime_error("bad --guest-output, expected tagged or raw");
      }
    } else if (arg == "--no-ebreak-stop") {
      options.stop_on_ebreak = false;
    } else if (arg == "--quiet") {
      options.quiet = true;
    } else if (arg == "--verbose") {
      options.verbose = true;
    } else if (!arg.empty() && arg.front() == '-') {
      throw std::runtime_error("unknown option `" + arg + "`");
    } else if (options.image_path.empty()) {
      options.image_path = fs::path(arg);
    } else {
      throw std::runtime_error("unexpected positional argument `" + arg + "`");
    }
  }

  if (options.image_path.empty()) {
    throw std::runtime_error("missing input image");
  }
  if (!fs::exists(options.image_path)) {
    throw std::runtime_error("input image does not exist: `" + options.image_path.string() + "`");
  }
  if (options.symbol_elf_path && !fs::exists(*options.symbol_elf_path)) {
    throw std::runtime_error("symbol ELF does not exist: `" + options.symbol_elf_path->string() + "`");
  }
  if (options.random_irq.min_delay > options.random_irq.max_delay) {
    throw std::runtime_error("--irq-delay min must be <= max");
  }
  if (options.random_irq.min_width == 0 || options.random_irq.max_width == 0) {
    throw std::runtime_error("--irq-width values must be non-zero");
  }
  if (options.random_irq.min_width > options.random_irq.max_width) {
    throw std::runtime_error("--irq-width min must be <= max");
  }
  return options;
}

}  // namespace cl1sim
