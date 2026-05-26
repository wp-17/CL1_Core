#ifndef SIM_VERILATOR_MEMORY_MODEL_H_
#define SIM_VERILATOR_MEMORY_MODEL_H_

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "common.h"
#include "options.h"

namespace cl1sim {

class MemoryModel {
 public:
  explicit MemoryModel(const Options& options);

  bool load_image(const fs::path& image_path, const std::string& image_type, uint32_t load_addr, StopInfo& stop);
  bool load_symbol_metadata(const fs::path& elf_path, StopInfo& stop);
  const std::optional<uint32_t>& tohost_addr() const;
  std::size_t loaded_bytes() const;
  bool peek_word(uint32_t addr, uint32_t& data) const;
  void observe_committed_write(uint32_t addr, uint32_t write_data, uint8_t mask, StopInfo& stop);
  PendingResponse handle_request(const BusRequest& request, bool is_fetch, StopInfo& stop);

 private:
  struct HexToken {
    bool is_address = false;
    std::string text;
    std::size_t bytes = 0;
  };

  std::string normalize_image_type(const fs::path& image_path, const std::string& image_type) const;
  static std::vector<uint8_t> read_file(const fs::path& path);
  bool load_bin(const fs::path& path, uint32_t load_addr, StopInfo& stop);
  bool load_hex(const fs::path& path, uint32_t load_addr, StopInfo& stop);
  bool load_elf(const fs::path& path, bool load_segments, StopInfo& stop);
  void store_blob(uint32_t base_addr, const uint8_t* data, std::size_t size);
  void store_byte(uint32_t addr, uint8_t value);
  bool load_byte(uint32_t addr, uint8_t& value) const;
  const AddressRegion* find_region(uint32_t addr) const;
  bool is_access_allowed(uint32_t addr, MemoryAccessKind kind) const;
  bool are_masked_bytes_accessible(uint32_t aligned_addr, uint8_t mask, MemoryAccessKind kind) const;
  bool is_in_ram(uint32_t addr) const;
  static uint32_t merge_word(uint32_t original, uint32_t write_data, uint8_t mask);
  bool read_word(uint32_t aligned_addr, uint8_t mask, uint32_t& data, bool is_fetch, StopInfo& stop) const;
  bool peek_word_internal(uint32_t aligned_addr, uint32_t& data) const;
  bool write_word(uint32_t aligned_addr, uint32_t write_data, uint8_t mask, StopInfo& stop);
  void write_guest_char(char ch);
  void set_tohost_stop(StopInfo& stop, bool commit_observed) const;

  uint32_t ram_base_;
  uint64_t ram_size_;
  bool has_uart_;
  uint32_t uart_addr_;
  bool has_host_exit_;
  uint32_t host_exit_addr_;
  GuestOutputMode guest_output_;
  bool guest_line_open_ = false;
  std::vector<AddressRegion> address_regions_;
  std::vector<uint8_t> ram_;
  std::unordered_map<uint32_t, uint8_t> sparse_memory_;
  std::optional<uint32_t> tohost_addr_;
  std::optional<uint32_t> fromhost_addr_;
  std::size_t loaded_bytes_ = 0;
  uint32_t host_exit_value_ = 0;
  uint32_t tohost_value_ = 0;
  uint32_t fromhost_value_ = 0;
};

}  // namespace cl1sim

#endif  // SIM_VERILATOR_MEMORY_MODEL_H_
