#include "common.h"

#include <array>
#include <algorithm>
#include <cctype>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace cl1sim {

vluint64_t g_sim_time = 0;

bool AddressRegion::contains(uint32_t addr) const {
  if (size == 0) {
    return false;
  }
  const uint64_t offset = static_cast<uint64_t>(addr) - static_cast<uint64_t>(base);
  return addr >= base && offset < size;
}

bool AddressRegion::allows(MemoryAccessKind kind) const {
  switch (kind) {
    case MemoryAccessKind::kFetch:
      return executable;
    case MemoryAccessKind::kRead:
      return readable;
    case MemoryAccessKind::kWrite:
      return writable;
  }
  return false;
}

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

std::vector<std::string> split_colon_separated(const std::string& text) {
  std::vector<std::string> parts;
  std::size_t start = 0;
  while (start <= text.size()) {
    const auto end = text.find(':', start);
    if (end == std::string::npos) {
      parts.push_back(text.substr(start));
      break;
    }
    parts.push_back(text.substr(start, end - start));
    start = end + 1;
  }
  return parts;
}

}  // namespace cl1sim

double sc_time_stamp() {
  return static_cast<double>(cl1sim::g_sim_time);
}
