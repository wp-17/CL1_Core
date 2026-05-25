#include "memory_model.h"

#include <elf.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

namespace cl1sim {

MemoryModel::MemoryModel(const Options& options)
    : ram_base_(options.ram_base),
      ram_size_(options.ram_size),
      has_uart_(options.has_uart),
      uart_addr_(options.uart_addr),
      has_host_exit_(options.has_host_exit),
      host_exit_addr_(options.host_exit_addr),
      guest_output_(options.guest_output),
      address_regions_(build_address_regions(options)),
      ram_(static_cast<std::size_t>(options.ram_size), 0) {}

bool MemoryModel::load_image(
    const fs::path& image_path, const std::string& image_type, uint32_t load_addr, StopInfo& stop) {
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

bool MemoryModel::load_symbol_metadata(const fs::path& elf_path, StopInfo& stop) {
  try {
    return load_elf(elf_path, false, stop);
  } catch (const std::exception& ex) {
    stop.kind = StopKind::kLoadError;
    stop.reason = ex.what();
    return false;
  }
}

const std::optional<uint32_t>& MemoryModel::tohost_addr() const {
  return tohost_addr_;
}

std::size_t MemoryModel::loaded_bytes() const {
  return loaded_bytes_;
}

bool MemoryModel::peek_word(uint32_t addr, uint32_t& data) const {
  return peek_word_internal(align_word(addr), data);
}

void MemoryModel::observe_committed_write(uint32_t addr, uint32_t write_data, uint8_t mask, StopInfo& stop) {
  if (stop.stopped() || mask == 0) {
    return;
  }

  const uint32_t aligned_addr = align_word(addr);
  if (tohost_addr_ && same_word(aligned_addr, *tohost_addr_)) {
    tohost_value_ = merge_word(tohost_value_, write_data, mask);
    if (tohost_value_ != 0u) {
      set_tohost_stop(stop, true);
    }
  }
}

PendingResponse MemoryModel::handle_request(const BusRequest& request, bool is_fetch, StopInfo& stop) {
  PendingResponse response;
  response.valid = true;

  const uint32_t aligned_addr = align_word(request.addr);
  if (request.wen) {
    response.err = !write_word(aligned_addr, request.data, request.mask, stop);
    response.data = 0;
  } else {
    uint32_t data = 0;
    response.err = !read_word(aligned_addr, request.mask, data, is_fetch, stop);
    response.data = data;
  }
  return response;
}

std::string MemoryModel::normalize_image_type(const fs::path& image_path, const std::string& image_type) const {
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

std::vector<uint8_t> MemoryModel::read_file(const fs::path& path) {
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

bool MemoryModel::load_bin(const fs::path& path, uint32_t load_addr, StopInfo&) {
  const auto data = read_file(path);
  store_blob(load_addr, data.data(), data.size());
  return true;
}

bool MemoryModel::load_hex(const fs::path& path, uint32_t load_addr, StopInfo&) {
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
      if (bytes > kWordBytes) {
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
    all_words &= token.bytes == kWordBytes;
  }

  const uint32_t address_scale = (saw_address_token && data_tokens > 0 && all_words) ? kWordBytes : 1u;
  uint32_t cursor = load_addr;
  for (const auto& token : tokens) {
    if (token.is_address) {
      const uint32_t raw_addr = static_cast<uint32_t>(parse_hex_token(token.text));
      cursor = (raw_addr < ram_base_) ? load_addr + raw_addr * address_scale : raw_addr;
      continue;
    }

    const uint32_t value = static_cast<uint32_t>(parse_hex_token(token.text));
    for (std::size_t i = 0; i < token.bytes; ++i) {
      store_byte(cursor + static_cast<uint32_t>(i), static_cast<uint8_t>((value >> (kByteBits * i)) & kByteMask));
    }
    cursor += static_cast<uint32_t>(token.bytes);
  }
  return true;
}

bool MemoryModel::load_elf(const fs::path& path, bool load_segments, StopInfo&) {
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

void MemoryModel::store_blob(uint32_t base_addr, const uint8_t* data, std::size_t size) {
  for (std::size_t i = 0; i < size; ++i) {
    store_byte(base_addr + static_cast<uint32_t>(i), data[i]);
  }
}

void MemoryModel::store_byte(uint32_t addr, uint8_t value) {
  if (is_in_ram(addr)) {
    ram_[static_cast<std::size_t>(addr - ram_base_)] = value;
  } else {
    sparse_memory_[addr] = value;
  }
  ++loaded_bytes_;
}

bool MemoryModel::load_byte(uint32_t addr, uint8_t& value) const {
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

const AddressRegion* MemoryModel::find_region(uint32_t addr) const {
  for (const auto& region : address_regions_) {
    if (region.contains(addr)) {
      return &region;
    }
  }
  return nullptr;
}

bool MemoryModel::is_access_allowed(uint32_t addr, MemoryAccessKind kind) const {
  const AddressRegion* region = find_region(addr);
  return region != nullptr && region->allows(kind);
}

bool MemoryModel::are_masked_bytes_accessible(uint32_t aligned_addr, uint8_t mask, MemoryAccessKind kind) const {
  if (mask == 0) {
    return true;
  }
  for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
    if (lane_selected(mask, lane) && !is_access_allowed(aligned_addr + lane, kind)) {
      return false;
    }
  }
  return true;
}

bool MemoryModel::is_in_ram(uint32_t addr) const {
  if (ram_size_ == 0) {
    return false;
  }
  const uint64_t offset = static_cast<uint64_t>(addr) - static_cast<uint64_t>(ram_base_);
  return addr >= ram_base_ && offset < ram_size_;
}

uint32_t MemoryModel::merge_word(uint32_t original, uint32_t write_data, uint8_t mask) {
  uint32_t merged = original;
  for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
    if (lane_selected(mask, lane)) {
      const uint32_t byte_mask = lane_byte_mask(lane);
      merged = (merged & ~byte_mask) | (write_data & byte_mask);
    }
  }
  return merged;
}

bool MemoryModel::read_word(uint32_t aligned_addr, uint8_t mask, uint32_t& data, bool is_fetch, StopInfo& stop) const {
  data = 0;
  const uint8_t effective_mask = is_fetch ? kFullWordMask : mask;
  const MemoryAccessKind access_kind = is_fetch ? MemoryAccessKind::kFetch : MemoryAccessKind::kRead;

  if (!are_masked_bytes_accessible(aligned_addr, effective_mask, access_kind)) {
    if (is_fetch) {
      stop.kind = StopKind::kFail;
      stop.reason = "instruction read from unmapped address " + hex32(aligned_addr);
    }
    return false;
  }

  if (has_uart_ && same_word(aligned_addr, uart_addr_)) {
    data = 0;
    return true;
  }
  if (has_host_exit_ && same_word(aligned_addr, host_exit_addr_)) {
    data = host_exit_value_;
    return true;
  }
  if (tohost_addr_ && same_word(aligned_addr, *tohost_addr_)) {
    data = tohost_value_;
    return true;
  }
  if (fromhost_addr_ && same_word(aligned_addr, *fromhost_addr_)) {
    data = fromhost_value_;
    return true;
  }

  for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
    uint8_t value = 0;
    if (load_byte(aligned_addr + lane, value)) {
      data |= static_cast<uint32_t>(value) << lane_shift(lane);
    }
  }
  return true;
}

bool MemoryModel::peek_word_internal(uint32_t aligned_addr, uint32_t& data) const {
  data = 0;
  bool any_byte = false;
  for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
    uint8_t value = 0;
    if (load_byte(aligned_addr + lane, value)) {
      data |= static_cast<uint32_t>(value) << lane_shift(lane);
      any_byte = true;
    }
  }
  return any_byte;
}

bool MemoryModel::write_word(uint32_t aligned_addr, uint32_t write_data, uint8_t mask, StopInfo& stop) {
  if (mask == 0) {
    return true;
  }

  if (!are_masked_bytes_accessible(aligned_addr, mask, MemoryAccessKind::kWrite)) {
    return false;
  }

  if (has_uart_ && same_word(aligned_addr, uart_addr_)) {
    for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
      if (lane_selected(mask, lane)) {
        const char ch = static_cast<char>(extract_byte(write_data, lane));
        write_guest_char(ch);
      }
    }
    std::cout.flush();
    return true;
  }

  if (has_host_exit_ && same_word(aligned_addr, host_exit_addr_)) {
    host_exit_value_ = merge_word(host_exit_value_, write_data, mask);
    stop.kind = (host_exit_value_ == 0u || host_exit_value_ == 1u) ? StopKind::kPass : StopKind::kFail;
    stop.code = host_exit_value_;
    stop.reason = "host exit register write " + hex32(host_exit_value_) + " at " + hex32(host_exit_addr_);
    return true;
  }

  uint32_t existing = 0;
  for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
    uint8_t value = 0;
    if (load_byte(aligned_addr + lane, value)) {
      existing |= static_cast<uint32_t>(value) << lane_shift(lane);
    }
  }

  const uint32_t merged = merge_word(existing, write_data, mask);
  for (uint32_t lane = 0; lane < kWordBytes; ++lane) {
    if (lane_selected(mask, lane)) {
      const uint8_t value = extract_byte(merged, lane);
      if (is_in_ram(aligned_addr + lane)) {
        ram_[static_cast<std::size_t>(aligned_addr + lane - ram_base_)] = value;
      } else {
        sparse_memory_[aligned_addr + lane] = value;
      }
    }
  }

  if (tohost_addr_ && same_word(aligned_addr, *tohost_addr_)) {
    tohost_value_ = merged;
    if (tohost_value_ != 0u) {
      set_tohost_stop(stop, false);
    }
  }

  if (fromhost_addr_ && same_word(aligned_addr, *fromhost_addr_)) {
    fromhost_value_ = merged;
  }

  return true;
}

void MemoryModel::write_guest_char(char ch) {
  if (guest_output_ == GuestOutputMode::kRaw) {
    std::cout.put(ch);
    return;
  }

  if (!guest_line_open_) {
    std::cout << "[guest] ";
    guest_line_open_ = true;
  }
  std::cout.put(ch);
  if (ch == '\n') {
    guest_line_open_ = false;
  }
}

void MemoryModel::set_tohost_stop(StopInfo& stop, bool commit_observed) const {
  stop.kind = (tohost_value_ == 1u) ? StopKind::kPass : StopKind::kFail;
  stop.code = tohost_value_;
  stop.reason = "tohost write " + hex32(tohost_value_) + " at " + hex32(*tohost_addr_);
  if (commit_observed) {
    stop.reason += " (commit observed)";
  }
}

}  // namespace cl1sim
