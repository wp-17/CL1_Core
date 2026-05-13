#include <elf.h>

#include <array>
#include <cerrno>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <verilated.h>
#include <verilated_fst_c.h>

#include "VCl1Top.h"

namespace fs = std::filesystem;

namespace {

vluint64_t g_sim_time = 0;

double sc_time_stamp() {
  return static_cast<double>(g_sim_time);
}

constexpr uint32_t kDefaultRamBase = 0x80000000u;
constexpr uint64_t kDefaultRamSize = 16ull * 1024ull * 1024ull;
constexpr uint32_t kDefaultLoadAddr = kDefaultRamBase;
constexpr uint32_t kDefaultUartAddr = 0x10000000u;
constexpr uint32_t kDefaultHostExitAddr = 0x10000004u;
constexpr uint64_t kDefaultMaxCycles = 1'000'000ull;
constexpr uint32_t kEbreakInsn = 0x00100073u;
constexpr uint32_t kCompressedEbreakInsn = 0x00009002u;

std::string hex32(uint32_t value) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setfill('0') << std::setw(8) << value;
  return oss.str();
}

std::string trim(std::string value) {
  const auto first = value.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) {
    return "";
  }
  const auto last = value.find_last_not_of(" \t\r\n");
  return value.substr(first, last - first + 1);
}

std::string strip_comments(std::string line) {
  const std::array<std::string, 3> markers = {"//", "#", ";"};
  std::size_t cut = std::string::npos;
  for (const auto& marker : markers) {
    const auto pos = line.find(marker);
    if (pos != std::string::npos) {
      cut = (cut == std::string::npos) ? pos : std::min(cut, pos);
    }
  }
  if (cut != std::string::npos) {
    line.erase(cut);
  }
  return trim(line);
}

bool is_hex_digits(const std::string& text) {
  if (text.empty()) {
    return false;
  }
  for (const unsigned char ch : text) {
    if (!std::isxdigit(ch)) {
      return false;
    }
  }
  return true;
}

uint64_t parse_hex_token(const std::string& text) {
  return std::stoull(text, nullptr, 16);
}

uint64_t parse_u64_arg(const std::string& text) {
  std::string number = trim(text);
  if (number.empty()) {
    throw std::runtime_error("empty numeric argument");
  }

  uint64_t multiplier = 1;
  const char suffix = number.back();
  if (suffix == 'k' || suffix == 'K') {
    multiplier = 1024ull;
    number.pop_back();
  } else if (suffix == 'm' || suffix == 'M') {
    multiplier = 1024ull * 1024ull;
    number.pop_back();
  } else if (suffix == 'g' || suffix == 'G') {
    multiplier = 1024ull * 1024ull * 1024ull;
    number.pop_back();
  }

  const uint64_t value = std::stoull(number, nullptr, 0);
  return value * multiplier;
}

bool ends_with_case_insensitive(const std::string& text, const std::string& suffix) {
  if (suffix.size() > text.size()) {
    return false;
  }
  const std::size_t start = text.size() - suffix.size();
  for (std::size_t i = 0; i < suffix.size(); ++i) {
    const unsigned char lhs = static_cast<unsigned char>(text[start + i]);
    const unsigned char rhs = static_cast<unsigned char>(suffix[i]);
    if (std::tolower(lhs) != std::tolower(rhs)) {
      return false;
    }
  }
  return true;
}

enum class StopKind {
  kRunning,
  kPass,
  kFail,
  kTimeout,
  kLoadError
};

struct StopInfo {
  StopKind kind = StopKind::kRunning;
  uint32_t code = 0;
  std::string reason;

  bool stopped() const {
    return kind != StopKind::kRunning;
  }
};

struct Options {
  fs::path image_path;
  std::optional<fs::path> symbol_elf_path;
  std::optional<fs::path> trace_path;
  std::optional<fs::path> commit_log_path;
  uint32_t ram_base = kDefaultRamBase;
  uint64_t ram_size = kDefaultRamSize;
  uint32_t load_addr = kDefaultLoadAddr;
  uint32_t uart_addr = kDefaultUartAddr;
  uint32_t host_exit_addr = kDefaultHostExitAddr;
  uint64_t max_cycles = kDefaultMaxCycles;
  std::string image_type = "auto";
  bool quiet = false;
  bool verbose = false;
};

struct BusRequest {
  uint32_t addr = 0;
  uint32_t data = 0;
  uint8_t mask = 0;
  uint8_t size = 0;
  bool wen = false;
};

struct PendingResponse {
  bool valid = false;
  uint32_t data = 0;
  bool err = false;
};

struct CycleSnapshot {
  bool ibus_req_fire = false;
  bool ibus_rsp_fire = false;
  bool dbus_req_fire = false;
  bool dbus_rsp_fire = false;
  bool ibus_req_valid = false;
  bool dbus_req_valid = false;
  bool ibus_rsp_valid = false;
  bool dbus_rsp_valid = false;
  bool ibus_rsp_ready = false;
  bool dbus_rsp_ready = false;
  BusRequest ibus_request;
  BusRequest dbus_request;
};

class MemoryModel {
 public:
  explicit MemoryModel(const Options& options)
      : ram_base_(options.ram_base),
        ram_size_(options.ram_size),
        uart_addr_(options.uart_addr),
        host_exit_addr_(options.host_exit_addr),
        ram_(static_cast<std::size_t>(options.ram_size), 0) {}

  bool load_image(const fs::path& image_path, const std::string& image_type, uint32_t load_addr, StopInfo& stop) {
    try {
      const std::string selected_type = normalize_image_type(image_path, image_type);
      if (selected_type == "elf") {
        return load_elf(image_path, true, stop);
      }
      if (selected_type == "bin") {
        return load_bin(image_path, load_addr, stop);
      }
      if (selected_type == "hex") {
        return load_hex(image_path, load_addr, stop);
      }
      stop.kind = StopKind::kLoadError;
      stop.reason = "unsupported image type `" + selected_type + "`";
      return false;
    } catch (const std::exception& ex) {
      stop.kind = StopKind::kLoadError;
      stop.reason = ex.what();
      return false;
    }
  }

  bool load_symbol_metadata(const fs::path& elf_path, StopInfo& stop) {
    try {
      return load_elf(elf_path, false, stop);
    } catch (const std::exception& ex) {
      stop.kind = StopKind::kLoadError;
      stop.reason = ex.what();
      return false;
    }
  }

  const std::optional<uint32_t>& tohost_addr() const {
    return tohost_addr_;
  }

  std::size_t loaded_bytes() const {
    return loaded_bytes_;
  }

  bool peek_word(uint32_t addr, uint32_t& data) const {
    return peek_word_internal(addr & ~0x3u, data);
  }

  PendingResponse handle_request(const BusRequest& request, bool is_fetch, StopInfo& stop) {
    PendingResponse response;
    response.valid = true;

    const uint32_t aligned_addr = request.addr & ~0x3u;
    if (request.wen) {
      response.err = !write_word(aligned_addr, request.data, request.mask, stop);
      response.data = 0;
    } else {
      uint32_t data = 0;
      response.err = !read_word(aligned_addr, data, is_fetch, stop);
      response.data = data;
    }
    return response;
  }

 private:
  struct HexToken {
    bool is_address = false;
    std::string text;
    std::size_t bytes = 0;
  };

  std::string normalize_image_type(const fs::path& image_path, const std::string& image_type) const {
    if (image_type != "auto") {
      return image_type;
    }

    const std::string filename = image_path.filename().string();
    if (ends_with_case_insensitive(filename, ".elf")) {
      return "elf";
    }
    if (ends_with_case_insensitive(filename, ".bin")) {
      return "bin";
    }
    if (ends_with_case_insensitive(filename, ".hex")) {
      return "hex";
    }
    throw std::runtime_error("cannot infer image type for `" + image_path.string() + "`");
  }

  static std::vector<uint8_t> read_file(const fs::path& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
      throw std::runtime_error("failed to open `" + path.string() + "`: " + std::strerror(errno));
    }
    file.seekg(0, std::ios::end);
    const std::streamoff size = file.tellg();
    file.seekg(0, std::ios::beg);
    if (size < 0) {
      throw std::runtime_error("failed to stat `" + path.string() + "`");
    }

    std::vector<uint8_t> data(static_cast<std::size_t>(size), 0);
    if (!data.empty()) {
      file.read(reinterpret_cast<char*>(data.data()), size);
      if (!file) {
        throw std::runtime_error("failed to read `" + path.string() + "`");
      }
    }
    return data;
  }

  bool load_bin(const fs::path& path, uint32_t load_addr, StopInfo&) {
    const auto data = read_file(path);
    store_blob(load_addr, data.data(), data.size());
    return true;
  }

  bool load_hex(const fs::path& path, uint32_t load_addr, StopInfo&) {
    std::ifstream file(path);
    if (!file) {
      throw std::runtime_error("failed to open `" + path.string() + "`");
    }

    std::vector<HexToken> tokens;
    std::string line;
    while (std::getline(file, line)) {
      line = strip_comments(line);
      if (line.empty()) {
        continue;
      }
      std::istringstream iss(line);
      std::string token;
      while (iss >> token) {
        if (token.empty()) {
          continue;
        }
        if (token.front() == '@') {
          const std::string addr_text = token.substr(1);
          if (!is_hex_digits(addr_text)) {
            throw std::runtime_error("bad HEX address token `" + token + "` in `" + path.string() + "`");
          }
          tokens.push_back(HexToken{true, addr_text, 0});
          continue;
        }
        if (!is_hex_digits(token)) {
          throw std::runtime_error("bad HEX data token `" + token + "` in `" + path.string() + "`");
        }
        const std::size_t bytes = std::max<std::size_t>(1, (token.size() + 1) / 2);
        if (bytes > 4) {
          throw std::runtime_error("HEX token wider than 32 bits is not supported: `" + token + "`");
        }
        tokens.push_back(HexToken{false, token, bytes});
      }
    }

    bool saw_address_token = false;
    bool all_words = true;
    std::size_t data_tokens = 0;
    for (const auto& token : tokens) {
      if (token.is_address) {
        saw_address_token = true;
        continue;
      }
      ++data_tokens;
      all_words &= token.bytes == 4;
    }

    const uint32_t address_scale = (saw_address_token && data_tokens > 0 && all_words) ? 4u : 1u;
    uint32_t cursor = load_addr;
    for (const auto& token : tokens) {
      if (token.is_address) {
        const uint32_t raw_addr = static_cast<uint32_t>(parse_hex_token(token.text));
        cursor = (raw_addr < ram_base_) ? load_addr + raw_addr * address_scale : raw_addr;
        continue;
      }

      const uint32_t value = static_cast<uint32_t>(parse_hex_token(token.text));
      for (std::size_t i = 0; i < token.bytes; ++i) {
        store_byte(cursor + static_cast<uint32_t>(i), static_cast<uint8_t>((value >> (8 * i)) & 0xFFu));
      }
      cursor += static_cast<uint32_t>(token.bytes);
    }
    return true;
  }

  bool load_elf(const fs::path& path, bool load_segments, StopInfo&) {
    const auto bytes = read_file(path);
    if (bytes.size() < sizeof(Elf32_Ehdr)) {
      throw std::runtime_error("`" + path.string() + "` is too small to be an ELF file");
    }

    const auto* ehdr = reinterpret_cast<const Elf32_Ehdr*>(bytes.data());
    if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) {
      throw std::runtime_error("`" + path.string() + "` is not an ELF file");
    }
    if (ehdr->e_ident[EI_CLASS] != ELFCLASS32) {
      throw std::runtime_error("`" + path.string() + "` is not an ELF32 image");
    }
    if (ehdr->e_ident[EI_DATA] != ELFDATA2LSB) {
      throw std::runtime_error("`" + path.string() + "` is not little-endian");
    }

    if (load_segments) {
      const std::size_t phdr_table_end =
          static_cast<std::size_t>(ehdr->e_phoff) + static_cast<std::size_t>(ehdr->e_phnum) * sizeof(Elf32_Phdr);
      if (phdr_table_end > bytes.size()) {
        throw std::runtime_error("program header table in `" + path.string() + "` is truncated");
      }

      for (std::size_t i = 0; i < ehdr->e_phnum; ++i) {
        const auto* phdr = reinterpret_cast<const Elf32_Phdr*>(
            bytes.data() + static_cast<std::size_t>(ehdr->e_phoff) + i * sizeof(Elf32_Phdr));
        if (phdr->p_type != PT_LOAD) {
          continue;
        }
        if (phdr->p_offset + phdr->p_filesz > bytes.size()) {
          throw std::runtime_error("segment " + std::to_string(i) + " in `" + path.string() + "` is truncated");
        }
        const uint32_t dst = phdr->p_paddr != 0 ? phdr->p_paddr : phdr->p_vaddr;
        store_blob(dst, bytes.data() + phdr->p_offset, phdr->p_filesz);
        for (uint32_t off = phdr->p_filesz; off < phdr->p_memsz; ++off) {
          store_byte(dst + off, 0);
        }
      }
    }

    const std::size_t shdr_table_end =
        static_cast<std::size_t>(ehdr->e_shoff) + static_cast<std::size_t>(ehdr->e_shnum) * sizeof(Elf32_Shdr);
    if (ehdr->e_shoff == 0 || shdr_table_end > bytes.size()) {
      return true;
    }

    const auto* shdrs = reinterpret_cast<const Elf32_Shdr*>(bytes.data() + ehdr->e_shoff);
    for (std::size_t i = 0; i < ehdr->e_shnum; ++i) {
      const auto& shdr = shdrs[i];
      if (shdr.sh_type != SHT_SYMTAB && shdr.sh_type != SHT_DYNSYM) {
        continue;
      }
      if (shdr.sh_entsize == 0 || shdr.sh_offset + shdr.sh_size > bytes.size() || shdr.sh_link >= ehdr->e_shnum) {
        continue;
      }

      const auto& strtab = shdrs[shdr.sh_link];
      if (strtab.sh_offset + strtab.sh_size > bytes.size()) {
        continue;
      }
      const char* strtab_data = reinterpret_cast<const char*>(bytes.data() + strtab.sh_offset);
      const std::size_t sym_count = shdr.sh_size / shdr.sh_entsize;
      for (std::size_t sym_idx = 0; sym_idx < sym_count; ++sym_idx) {
        const auto* sym = reinterpret_cast<const Elf32_Sym*>(bytes.data() + shdr.sh_offset + sym_idx * shdr.sh_entsize);
        if (sym->st_name >= strtab.sh_size) {
          continue;
        }
        const std::string name = std::string(strtab_data + sym->st_name);
        if (name == "tohost") {
          tohost_addr_ = sym->st_value;
        } else if (name == "fromhost") {
          fromhost_addr_ = sym->st_value;
        }
      }
    }

    return true;
  }

  void store_blob(uint32_t base_addr, const uint8_t* data, std::size_t size) {
    for (std::size_t i = 0; i < size; ++i) {
      store_byte(base_addr + static_cast<uint32_t>(i), data[i]);
    }
  }

  void store_byte(uint32_t addr, uint8_t value) {
    if (is_in_ram(addr)) {
      ram_[static_cast<std::size_t>(addr - ram_base_)] = value;
    } else {
      sparse_memory_[addr] = value;
    }
    ++loaded_bytes_;
  }

  bool load_byte(uint32_t addr, uint8_t& value) const {
    if (is_in_ram(addr)) {
      value = ram_[static_cast<std::size_t>(addr - ram_base_)];
      return true;
    }
    const auto it = sparse_memory_.find(addr);
    if (it != sparse_memory_.end()) {
      value = it->second;
      return true;
    }
    return false;
  }

  bool is_in_ram(uint32_t addr) const {
    if (ram_size_ == 0) {
      return false;
    }
    const uint64_t offset = static_cast<uint64_t>(addr) - static_cast<uint64_t>(ram_base_);
    return addr >= ram_base_ && offset < ram_size_;
  }

  static uint32_t merge_word(uint32_t original, uint32_t write_data, uint8_t mask) {
    uint32_t merged = original;
    for (int lane = 0; lane < 4; ++lane) {
      if ((mask >> lane) & 0x1u) {
        const uint32_t byte_mask = 0xFFu << (lane * 8);
        merged = (merged & ~byte_mask) | (write_data & byte_mask);
      }
    }
    return merged;
  }

  bool read_word(uint32_t aligned_addr, uint32_t& data, bool is_fetch, StopInfo& stop) const {
    data = 0;

    if ((aligned_addr & ~0x3u) == (uart_addr_ & ~0x3u)) {
      data = 0;
      return true;
    }
    if ((aligned_addr & ~0x3u) == (host_exit_addr_ & ~0x3u)) {
      data = host_exit_value_;
      return true;
    }
    if (tohost_addr_ && (aligned_addr & ~0x3u) == (*tohost_addr_ & ~0x3u)) {
      data = tohost_value_;
      return true;
    }
    if (fromhost_addr_ && (aligned_addr & ~0x3u) == (*fromhost_addr_ & ~0x3u)) {
      data = fromhost_value_;
      return true;
    }

    bool any_byte = false;
    for (int lane = 0; lane < 4; ++lane) {
      uint8_t value = 0;
      if (load_byte(aligned_addr + static_cast<uint32_t>(lane), value)) {
        data |= static_cast<uint32_t>(value) << (lane * 8);
        any_byte = true;
      }
    }

    if (!any_byte) {
      stop.kind = StopKind::kFail;
      stop.reason = std::string(is_fetch ? "instruction" : "data") + " read from unmapped address " + hex32(aligned_addr);
      return false;
    }
    return true;
  }

  bool peek_word_internal(uint32_t aligned_addr, uint32_t& data) const {
    data = 0;
    bool any_byte = false;
    for (int lane = 0; lane < 4; ++lane) {
      uint8_t value = 0;
      if (load_byte(aligned_addr + static_cast<uint32_t>(lane), value)) {
        data |= static_cast<uint32_t>(value) << (lane * 8);
        any_byte = true;
      }
    }
    return any_byte;
  }

  bool write_word(uint32_t aligned_addr, uint32_t write_data, uint8_t mask, StopInfo& stop) {
    if (mask == 0) {
      return true;
    }

    if ((aligned_addr & ~0x3u) == (uart_addr_ & ~0x3u)) {
      for (int lane = 0; lane < 4; ++lane) {
        if ((mask >> lane) & 0x1u) {
          const char ch = static_cast<char>((write_data >> (lane * 8)) & 0xFFu);
          std::cout.put(ch);
        }
      }
      std::cout.flush();
      return true;
    }

    if ((aligned_addr & ~0x3u) == (host_exit_addr_ & ~0x3u)) {
      host_exit_value_ = merge_word(host_exit_value_, write_data, mask);
      stop.kind = (host_exit_value_ == 0u || host_exit_value_ == 1u) ? StopKind::kPass : StopKind::kFail;
      stop.code = host_exit_value_;
      stop.reason = "host exit register write " + hex32(host_exit_value_) + " at " + hex32(host_exit_addr_);
      return true;
    }

    uint32_t existing = 0;
    bool known_backing = false;
    if (is_in_ram(aligned_addr)) {
      known_backing = true;
      for (int lane = 0; lane < 4; ++lane) {
        existing |= static_cast<uint32_t>(ram_[static_cast<std::size_t>(aligned_addr + lane - ram_base_)]) << (lane * 8);
      }
    } else {
      for (int lane = 0; lane < 4; ++lane) {
        uint8_t value = 0;
        if (load_byte(aligned_addr + static_cast<uint32_t>(lane), value)) {
          existing |= static_cast<uint32_t>(value) << (lane * 8);
          known_backing = true;
        }
      }
    }

    if (!known_backing) {
      stop.kind = StopKind::kFail;
      stop.reason = "write to unmapped address " + hex32(aligned_addr);
      return false;
    }

    const uint32_t merged = merge_word(existing, write_data, mask);
    for (int lane = 0; lane < 4; ++lane) {
      if ((mask >> lane) & 0x1u) {
        const uint8_t value = static_cast<uint8_t>((merged >> (lane * 8)) & 0xFFu);
        if (is_in_ram(aligned_addr + static_cast<uint32_t>(lane))) {
          ram_[static_cast<std::size_t>(aligned_addr + lane - ram_base_)] = value;
        } else {
          sparse_memory_[aligned_addr + static_cast<uint32_t>(lane)] = value;
        }
      }
    }

    if (tohost_addr_ && (aligned_addr & ~0x3u) == (*tohost_addr_ & ~0x3u)) {
      tohost_value_ = merged;
      if (tohost_value_ != 0u) {
        stop.kind = (tohost_value_ == 1u) ? StopKind::kPass : StopKind::kFail;
        stop.code = tohost_value_;
        stop.reason = "tohost write " + hex32(tohost_value_) + " at " + hex32(*tohost_addr_);
      }
    }

    if (fromhost_addr_ && (aligned_addr & ~0x3u) == (*fromhost_addr_ & ~0x3u)) {
      fromhost_value_ = merged;
    }

    return true;
  }

  uint32_t ram_base_;
  uint64_t ram_size_;
  uint32_t uart_addr_;
  uint32_t host_exit_addr_;
  std::vector<uint8_t> ram_;
  std::unordered_map<uint32_t, uint8_t> sparse_memory_;
  std::optional<uint32_t> tohost_addr_;
  std::optional<uint32_t> fromhost_addr_;
  std::size_t loaded_bytes_ = 0;
  uint32_t host_exit_value_ = 0;
  uint32_t tohost_value_ = 0;
  uint32_t fromhost_value_ = 0;
};

class Simulator {
 public:
  explicit Simulator(Options options)
      : options_(std::move(options)), memory_(options_), top_(std::make_unique<VCl1Top>()) {
    gpr_shadow_.fill(0);
  }

  int run() {
    StopInfo stop;
    if (!memory_.load_image(options_.image_path, options_.image_type, options_.load_addr, stop)) {
      report(stop);
      return exit_code(stop);
    }

    if (options_.symbol_elf_path) {
      if (!memory_.load_symbol_metadata(*options_.symbol_elf_path, stop)) {
        report(stop);
        return exit_code(stop);
      }
    }

    if (!options_.quiet) {
      std::cerr << "[sim] loaded " << memory_.loaded_bytes() << " bytes from " << options_.image_path << "\n";
      if (memory_.tohost_addr()) {
        std::cerr << "[sim] tohost detected at " << hex32(*memory_.tohost_addr()) << "\n";
      }
    }

    setup_trace();
    open_commit_log();
    initialize_inputs();
    reset_core(8);

    while (!stop.stopped()) {
      tick(stop);
      if (Verilated::gotFinish()) {
        stop.kind = StopKind::kFail;
        stop.reason = "simulation ended via $finish";
      } else if (cycle_count_ >= options_.max_cycles) {
        stop.kind = StopKind::kTimeout;
        stop.reason = "timeout after " + std::to_string(options_.max_cycles) + " cycles";
      }
    }

    top_->final();
    close_commit_log();
    close_trace();
    report(stop);
    return exit_code(stop);
  }

 private:
  void initialize_inputs() {
    top_->clock = 0;
    top_->reset = 1;
    top_->io_ext_irq = 0;
    top_->io_sft_irq = 0;
    top_->io_tmr_irq = 0;
    top_->io_dbg_req_i = 0;
    top_->io_ibus_req_ready = 0;
    top_->io_ibus_rsp_valid = 0;
    top_->io_ibus_rsp_bits_data = 0;
    top_->io_ibus_rsp_bits_err = 0;
    top_->io_dbus_req_ready = 0;
    top_->io_dbus_rsp_valid = 0;
    top_->io_dbus_rsp_bits_data = 0;
    top_->io_dbus_rsp_bits_err = 0;
  }

  void setup_trace() {
    if (!options_.trace_path) {
      return;
    }
    Verilated::traceEverOn(true);
    trace_ = std::make_unique<VerilatedFstC>();
    top_->trace(trace_.get(), 99);
    trace_->open(options_.trace_path->string().c_str());
  }

  void close_trace() {
    if (trace_) {
      trace_->close();
      trace_.reset();
    }
  }

  void open_commit_log() {
    if (!options_.commit_log_path) {
      return;
    }
    commit_log_ = std::make_unique<std::ofstream>(options_.commit_log_path->string(), std::ios::out | std::ios::trunc);
    if (!commit_log_ || !commit_log_->good()) {
      throw std::runtime_error("failed to open commit log `" + options_.commit_log_path->string() + "`");
    }
  }

  void close_commit_log() {
    if (commit_log_) {
      commit_log_->close();
      commit_log_.reset();
    }
  }

  void emit_commit_log() {
    if (!commit_log_) {
      return;
    }
    (*commit_log_) << "CMT order=" << top_->rvfi_order
                   << " pc=" << hex32(top_->rvfi_pc_rdata)
                   << " insn=" << hex32(top_->rvfi_insn)
                   << " rd=" << std::dec << static_cast<unsigned>(top_->rvfi_rd_addr & 0x1Fu)
                   << " wdata=" << hex32(top_->rvfi_rd_wdata)
                   << " trap=" << static_cast<unsigned>(top_->rvfi_trap)
                   << "\n";
  }

  void dump_trace() {
    if (trace_) {
      trace_->dump(g_sim_time);
    }
  }

  void reset_core(int cycles) {
    // The generated top expects an active-low external reset because `RST_ACTIVELOW=true`.
    top_->reset = 0;
    for (int i = 0; i < cycles; ++i) {
      tick_internal();
    }
    top_->reset = 1;
    tick_internal();
  }

  void tick(StopInfo& stop) {
    const CycleSnapshot snapshot = tick_internal();
    log_activity(snapshot);
    observe_commit(stop);
    observe_trap_stop(stop);
    complete_bus_handshakes(snapshot, stop);
    ++cycle_count_;
  }

  CycleSnapshot tick_internal() {
    drive_memory_side();

    top_->clock = 0;
    top_->eval();
    const CycleSnapshot snapshot = sample_cycle_snapshot();
    dump_trace();
    g_sim_time += 5;

    top_->clock = 1;
    top_->eval();
    dump_trace();
    g_sim_time += 5;
    return snapshot;
  }

  void drive_memory_side() {
    top_->io_ibus_req_ready = ibus_pending_.valid ? 0 : 1;
    top_->io_dbus_req_ready = dbus_pending_.valid ? 0 : 1;

    top_->io_ibus_rsp_valid = ibus_pending_.valid ? 1 : 0;
    top_->io_ibus_rsp_bits_data = ibus_pending_.data;
    top_->io_ibus_rsp_bits_err = ibus_pending_.err ? 1 : 0;

    top_->io_dbus_rsp_valid = dbus_pending_.valid ? 1 : 0;
    top_->io_dbus_rsp_bits_data = dbus_pending_.data;
    top_->io_dbus_rsp_bits_err = dbus_pending_.err ? 1 : 0;
  }

  CycleSnapshot sample_cycle_snapshot() const {
    CycleSnapshot snapshot;

    snapshot.ibus_req_valid = top_->io_ibus_req_valid;
    snapshot.dbus_req_valid = top_->io_dbus_req_valid;
    snapshot.ibus_rsp_valid = ibus_pending_.valid;
    snapshot.dbus_rsp_valid = dbus_pending_.valid;
    snapshot.ibus_rsp_ready = top_->io_ibus_rsp_ready;
    snapshot.dbus_rsp_ready = top_->io_dbus_rsp_ready;

    if (snapshot.ibus_req_valid && top_->io_ibus_req_ready) {
      snapshot.ibus_req_fire = true;
      snapshot.ibus_request.addr = top_->io_ibus_req_bits_addr;
      snapshot.ibus_request.data = top_->io_ibus_req_bits_data;
      snapshot.ibus_request.mask = top_->io_ibus_req_bits_mask;
      snapshot.ibus_request.size = top_->io_ibus_req_bits_size;
      snapshot.ibus_request.wen = top_->io_ibus_req_bits_wen;
    }

    if (snapshot.dbus_req_valid && top_->io_dbus_req_ready) {
      snapshot.dbus_req_fire = true;
      snapshot.dbus_request.addr = top_->io_dbus_req_bits_addr;
      snapshot.dbus_request.data = top_->io_dbus_req_bits_data;
      snapshot.dbus_request.mask = top_->io_dbus_req_bits_mask;
      snapshot.dbus_request.size = top_->io_dbus_req_bits_size;
      snapshot.dbus_request.wen = top_->io_dbus_req_bits_wen;
    }

    snapshot.ibus_rsp_fire = snapshot.ibus_rsp_valid && snapshot.ibus_rsp_ready;
    snapshot.dbus_rsp_fire = snapshot.dbus_rsp_valid && snapshot.dbus_rsp_ready;
    return snapshot;
  }

  void complete_bus_handshakes(const CycleSnapshot& snapshot, StopInfo& stop) {
    if (snapshot.ibus_rsp_fire) {
      ibus_pending_.valid = false;
    }
    if (snapshot.dbus_rsp_fire) {
      dbus_pending_.valid = false;
    }

    if (!stop.stopped() && snapshot.ibus_req_fire) {
      ibus_pending_ = memory_.handle_request(snapshot.ibus_request, true, stop);
    }

    if (!stop.stopped() && snapshot.dbus_req_fire) {
      dbus_pending_ = memory_.handle_request(snapshot.dbus_request, false, stop);
    }
  }

  void observe_commit(StopInfo& stop) {
    if (stop.stopped()) {
      return;
    }
    if (!top_->rvfi_valid) {
      return;
    }

    emit_commit_log();

    const uint32_t rd = top_->rvfi_rd_addr & 0x1Fu;
    if (rd != 0) {
      gpr_shadow_[rd] = top_->rvfi_rd_wdata;
    }
    gpr_shadow_[0] = 0;

    const uint32_t insn = top_->rvfi_insn;
    if (insn == kEbreakInsn || insn == kCompressedEbreakInsn) {
      stop.kind = (gpr_shadow_[10] == 0u) ? StopKind::kPass : StopKind::kFail;
      stop.code = gpr_shadow_[10];
      stop.reason = "ebreak at pc=" + hex32(top_->rvfi_pc_rdata) + " with a0=" + hex32(gpr_shadow_[10]);
    }
  }

  void observe_trap_stop(StopInfo& stop) {
    if (stop.stopped()) {
      return;
    }

    if (top_->rvfi_pc_wdata != 0x20000000u) {
      return;
    }

    uint32_t word = 0;
    if (!memory_.peek_word(top_->rvfi_pc_rdata, word)) {
      return;
    }

    const bool is_ebreak32 = word == kEbreakInsn;
    const uint16_t halfword = static_cast<uint16_t>((word >> ((top_->rvfi_pc_rdata & 0x2u) ? 16 : 0)) & 0xFFFFu);
    const bool is_ebreak16 = halfword == static_cast<uint16_t>(kCompressedEbreakInsn);
    if (!is_ebreak32 && !is_ebreak16) {
      return;
    }

    stop.kind = (gpr_shadow_[10] == 0u) ? StopKind::kPass : StopKind::kFail;
    stop.code = gpr_shadow_[10];
    stop.reason = "ebreak trap at pc=" + hex32(top_->rvfi_pc_rdata) + " with a0=" + hex32(gpr_shadow_[10]);
  }

  void log_activity(const CycleSnapshot& snapshot) const {
    if (!options_.verbose) {
      return;
    }
    const bool interesting =
        snapshot.ibus_req_valid || snapshot.ibus_rsp_valid || snapshot.dbus_req_valid || snapshot.dbus_rsp_valid ||
        top_->rvfi_valid || cycle_count_ < 8;
    if (!interesting) {
      return;
    }

    std::cerr << "[sim][cycle " << cycle_count_ << "] "
              << "rst=" << static_cast<int>(top_->reset)
              << " ibus(req_v=" << static_cast<int>(snapshot.ibus_req_valid)
              << " req_r=" << static_cast<int>(top_->io_ibus_req_ready)
              << " addr=" << hex32(top_->io_ibus_req_bits_addr)
              << " rsp_v=" << static_cast<int>(snapshot.ibus_rsp_valid)
              << " rsp_r=" << static_cast<int>(snapshot.ibus_rsp_ready)
              << ") dbus(req_v=" << static_cast<int>(snapshot.dbus_req_valid)
              << " req_r=" << static_cast<int>(top_->io_dbus_req_ready)
              << " wen=" << static_cast<int>(top_->io_dbus_req_bits_wen)
              << " addr=" << hex32(top_->io_dbus_req_bits_addr)
              << " rsp_v=" << static_cast<int>(snapshot.dbus_rsp_valid)
              << " rsp_r=" << static_cast<int>(snapshot.dbus_rsp_ready)
              << ") rvfi_v=" << static_cast<int>(top_->rvfi_valid)
              << " pc_r=" << hex32(top_->rvfi_pc_rdata)
              << " pc_w=" << hex32(top_->rvfi_pc_wdata)
              << "\n";
  }

  void report(const StopInfo& stop) const {
    const char* tag = nullptr;
    switch (stop.kind) {
      case StopKind::kPass:
        tag = "PASS";
        break;
      case StopKind::kFail:
        tag = "FAIL";
        break;
      case StopKind::kTimeout:
        tag = "TIMEOUT";
        break;
      case StopKind::kLoadError:
        tag = "LOAD-ERROR";
        break;
      case StopKind::kRunning:
        tag = "RUNNING";
        break;
    }

    std::ostream& os = (stop.kind == StopKind::kPass) ? std::cout : std::cerr;
    os << "[sim] " << tag << " after " << cycle_count_ << " cycles";
    if (!stop.reason.empty()) {
      os << ": " << stop.reason;
    }
    if (stop.code != 0) {
      os << " (code=" << hex32(stop.code) << ")";
    }
    os << "\n";
  }

  static int exit_code(const StopInfo& stop) {
    switch (stop.kind) {
      case StopKind::kPass:
        return 0;
      case StopKind::kTimeout:
        return 2;
      case StopKind::kFail:
      case StopKind::kLoadError:
      case StopKind::kRunning:
      default:
        return 1;
    }
  }

  Options options_;
  MemoryModel memory_;
  std::unique_ptr<VCl1Top> top_;
  std::unique_ptr<VerilatedFstC> trace_;
  std::unique_ptr<std::ofstream> commit_log_;
  std::array<uint32_t, 32> gpr_shadow_{};
  PendingResponse ibus_pending_{};
  PendingResponse dbus_pending_{};
  uint64_t cycle_count_ = 0;
};

void print_usage(const char* argv0) {
  std::cerr
      << "Usage: " << argv0 << " [options] <image>\n"
      << "Options:\n"
      << "  --image-type <auto|elf|bin|hex>  Force image parser (default: auto)\n"
      << "  --symbol-elf <path>              Parse tohost/fromhost symbols from a sidecar ELF\n"
      << "  --load-addr <addr>               Base load address for BIN/HEX images (default: 0x80000000)\n"
      << "  --ram-base <addr>                Simulated RAM base (default: 0x80000000)\n"
      << "  --ram-size <bytes>               Simulated RAM size, supports K/M/G suffixes (default: 16M)\n"
      << "  --host-exit-addr <addr>          MMIO exit register address (default: 0x10000004)\n"
      << "  --uart-addr <addr>               MMIO UART TX register address (default: 0x10000000)\n"
      << "  --max-cycles <count>             Timeout in cycles (default: 1000000)\n"
      << "  --trace <path.fst>               Dump an FST waveform\n"
      << "  --commit-log <path>              Dump RVFI commit log lines\n"
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
      options.host_exit_addr = static_cast<uint32_t>(parse_u64_arg(need_value(arg)));
    } else if (arg == "--uart-addr") {
      options.uart_addr = static_cast<uint32_t>(parse_u64_arg(need_value(arg)));
    } else if (arg == "--max-cycles") {
      options.max_cycles = parse_u64_arg(need_value(arg));
    } else if (arg == "--trace") {
      options.trace_path = fs::path(need_value(arg));
    } else if (arg == "--commit-log") {
      options.commit_log_path = fs::path(need_value(arg));
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
  return options;
}

}  // namespace

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);

  try {
    Options options = parse_args(argc, argv);
    Simulator simulator(std::move(options));
    return simulator.run();
  } catch (const std::exception& ex) {
    std::cerr << "[sim] argument error: " << ex.what() << "\n";
    print_usage(argv[0]);
    return 1;
  }
}
