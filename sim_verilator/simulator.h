#ifndef SIM_VERILATOR_SIMULATOR_H_
#define SIM_VERILATOR_SIMULATOR_H_

#include <array>
#include <cstdint>
#include <fstream>
#include <memory>
#include <optional>
#include <string>

#include <verilated_fst_c.h>

#include "common.h"
#include "memory_model.h"
#include "options.h"
#include "random_irq.h"

class VCl1Top;

namespace cl1sim {

#if defined(CL1_TEST_MODE_CACHE)
enum class AxiReadState {
  kIdle,
  kData
};

enum class AxiWriteState {
  kIdle,
  kData,
  kResp
};

struct AxiReadContext {
  AxiReadState state = AxiReadState::kIdle;
  uint32_t addr = 0;
  uint8_t len = 0;
  uint8_t beat = 0;
  uint8_t size = 2;
  uint32_t data = 0;
  bool err = false;
};

struct AxiWriteContext {
  AxiWriteState state = AxiWriteState::kIdle;
  uint32_t addr = 0;
  uint8_t len = 0;
  uint8_t beat = 0;
  uint8_t size = 2;
  bool err = false;
};
#endif

struct CycleSnapshot {
#if defined(CL1_TEST_MODE_BUS)
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
#else
  bool ar_fire = false;
  bool r_fire = false;
  bool aw_fire = false;
  bool w_fire = false;
  bool b_fire = false;
  bool ar_valid = false;
  bool r_valid = false;
  bool aw_valid = false;
  bool w_valid = false;
  bool b_valid = false;
  bool r_ready = false;
  bool b_ready = false;
  BusRequest read_request;
  BusRequest write_request;
  uint8_t ar_len = 0;
  uint8_t r_beat = 0;
  uint8_t aw_len = 0;
  uint8_t w_beat = 0;
#endif
};

class Simulator {
 public:
  explicit Simulator(Options options);
  ~Simulator();

  int run();

 private:
  void initialize_inputs();
  void drive_interrupts();
  void setup_trace();
  void close_trace();
  void open_commit_log();
  void close_commit_log();
  void emit_commit_log();
  void dump_trace();
  void reset_core(int cycles);
  void tick(StopInfo& stop);
  CycleSnapshot tick_internal();
  void drive_memory_side();
  CycleSnapshot sample_cycle_snapshot() const;
  static uint32_t axi_next_addr(uint32_t base, uint8_t beat, uint8_t size);
  void prepare_axi_read_beat(StopInfo& stop);
  void complete_memory_handshakes(const CycleSnapshot& snapshot, StopInfo& stop);
  void observe_commit(StopInfo& stop);
  void observe_trap_stop(StopInfo& stop);
  void set_ebreak_stop(StopInfo& stop, const std::string& label) const;
  void log_activity(const CycleSnapshot& snapshot) const;
  void report(const StopInfo& stop) const;
  static int exit_code(const StopInfo& stop);

  Options options_;
  MemoryModel memory_;
  RandomIrqGenerator random_irq_;
  std::unique_ptr<VCl1Top> top_;
  std::unique_ptr<VerilatedFstC> trace_;
  std::unique_ptr<std::ofstream> commit_log_;
  std::array<uint32_t, 32> gpr_shadow_{};
  std::optional<uint64_t> last_commit_order_;
#if defined(CL1_TEST_MODE_BUS)
  PendingResponse ibus_pending_{};
  PendingResponse dbus_pending_{};
#else
  AxiReadContext axi_read_{};
  AxiWriteContext axi_write_{};
#endif
  uint64_t cycle_count_ = 0;
};

}  // namespace cl1sim

#endif  // SIM_VERILATOR_SIMULATOR_H_
