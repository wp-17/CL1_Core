#ifndef SIM_VERILATOR_COMMON_H_
#define SIM_VERILATOR_COMMON_H_

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#include <verilated.h>

#if !defined(CL1_TEST_MODE_BUS) && !defined(CL1_TEST_MODE_CACHE)
#define CL1_TEST_MODE_BUS 1
#endif

namespace cl1sim {

namespace fs = std::filesystem;

extern vluint64_t g_sim_time;

inline constexpr uint64_t kDefaultMaxCycles = 1'000'000ull;
inline constexpr uint32_t kEbreakInsn = 0x00100073u;
inline constexpr uint32_t kCompressedEbreakInsn = 0x00009002u;
inline constexpr uint32_t kTrapVectorAddress = 0x20000000u;
inline constexpr uint32_t kWordBytes = 4;
inline constexpr uint32_t kWordAlignMask = kWordBytes - 1;
inline constexpr uint32_t kByteBits = 8;
inline constexpr uint32_t kByteMask = 0xFFu;
inline constexpr uint32_t kHalfwordMask = 0xFFFFu;
inline constexpr uint8_t kFullWordMask = 0xFu;
inline constexpr uint32_t kGprIndexMask = 0x1Fu;
inline constexpr uint32_t kA0Register = 10;
inline constexpr uint8_t kMaxAxiTransferSizeLog2 = 4;

constexpr uint32_t align_word(uint32_t addr) {
  return addr & ~kWordAlignMask;
}

constexpr bool same_word(uint32_t lhs, uint32_t rhs) {
  return align_word(lhs) == align_word(rhs);
}

constexpr bool lane_selected(uint8_t mask, uint32_t lane) {
  return ((mask >> lane) & 0x1u) != 0;
}

constexpr uint32_t lane_shift(uint32_t lane) {
  return lane * kByteBits;
}

constexpr uint32_t lane_byte_mask(uint32_t lane) {
  return kByteMask << lane_shift(lane);
}

constexpr uint8_t extract_byte(uint32_t word, uint32_t lane) {
  return static_cast<uint8_t>((word >> lane_shift(lane)) & kByteMask);
}

enum class MemoryAccessKind {
  kFetch,
  kRead,
  kWrite
};

struct AddressRegion {
  std::string name;
  uint32_t base = 0;
  uint64_t size = 0;
  bool readable = true;
  bool writable = true;
  bool executable = true;

  bool contains(uint32_t addr) const;
  bool allows(MemoryAccessKind kind) const;
};

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

enum class GuestOutputMode {
  kTagged,
  kRaw
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

std::string hex32(uint32_t value);
std::string trim(std::string value);
std::string strip_comments(std::string line);
bool is_hex_digits(const std::string& text);
uint64_t parse_hex_token(const std::string& text);
uint64_t parse_u64_arg(const std::string& text);
bool ends_with_case_insensitive(const std::string& text, const std::string& suffix);
std::vector<std::string> split_colon_separated(const std::string& text);

}  // namespace cl1sim

double sc_time_stamp();

#endif  // SIM_VERILATOR_COMMON_H_
