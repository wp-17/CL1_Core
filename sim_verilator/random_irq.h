#ifndef SIM_VERILATOR_RANDOM_IRQ_H_
#define SIM_VERILATOR_RANDOM_IRQ_H_

#include <cstdint>

namespace cl1sim {

struct IrqSignals {
  bool ext = false;
  bool sft = false;
  bool tmr = false;
};

struct RandomIrqConfig {
  IrqSignals enabled;
  uint64_t seed = 1;
  uint64_t min_delay = 8;
  uint64_t max_delay = 64;
  uint64_t min_width = 1;
  uint64_t max_width = 4;

  bool any_enabled() const {
    return enabled.ext || enabled.sft || enabled.tmr;
  }
};

struct RandomIrqLineState {
  bool enabled = false;
  uint64_t delay_remaining = 0;
  uint64_t width_remaining = 0;
};

class RandomIrqGenerator {
 public:
  explicit RandomIrqGenerator(const RandomIrqConfig& config);

  IrqSignals step();

 private:
  uint64_t next_u64();
  uint64_t uniform(uint64_t min_value, uint64_t max_value);
  void reset_line(RandomIrqLineState& line);
  void schedule_next(RandomIrqLineState& line);
  bool step_line(RandomIrqLineState& line);

  RandomIrqConfig config_;
  uint64_t rng_state_;
  RandomIrqLineState ext_;
  RandomIrqLineState sft_;
  RandomIrqLineState tmr_;
};

}  // namespace cl1sim

#endif  // SIM_VERILATOR_RANDOM_IRQ_H_
