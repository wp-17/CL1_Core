#include "random_irq.h"

namespace cl1sim {

RandomIrqGenerator::RandomIrqGenerator(const RandomIrqConfig& config)
    : config_(config), rng_state_(config.seed) {
  if (rng_state_ == 0) {
    rng_state_ = 0x9e3779b97f4a7c15ull;
  }
  ext_.enabled = config.enabled.ext;
  sft_.enabled = config.enabled.sft;
  tmr_.enabled = config.enabled.tmr;
  reset_line(ext_);
  reset_line(sft_);
  reset_line(tmr_);
}

IrqSignals RandomIrqGenerator::step() {
  return IrqSignals{
      step_line(ext_),
      step_line(sft_),
      step_line(tmr_),
  };
}

uint64_t RandomIrqGenerator::next_u64() {
  uint64_t x = rng_state_;
  x ^= x << 13;
  x ^= x >> 7;
  x ^= x << 17;
  rng_state_ = x;
  return x;
}

uint64_t RandomIrqGenerator::uniform(uint64_t min_value, uint64_t max_value) {
  if (max_value <= min_value) {
    return min_value;
  }
  return min_value + (next_u64() % (max_value - min_value + 1));
}

void RandomIrqGenerator::reset_line(RandomIrqLineState& line) {
  line.width_remaining = 0;
  line.delay_remaining = line.enabled ? uniform(config_.min_delay, config_.max_delay) : 0;
}

void RandomIrqGenerator::schedule_next(RandomIrqLineState& line) {
  line.delay_remaining = uniform(config_.min_delay, config_.max_delay);
}

bool RandomIrqGenerator::step_line(RandomIrqLineState& line) {
  if (!line.enabled) {
    return false;
  }
  if (line.width_remaining != 0) {
    --line.width_remaining;
    if (line.width_remaining == 0) {
      schedule_next(line);
    }
    return true;
  }
  if (line.delay_remaining != 0) {
    --line.delay_remaining;
    return false;
  }

  line.width_remaining = uniform(config_.min_width, config_.max_width);
  --line.width_remaining;
  if (line.width_remaining == 0) {
    schedule_next(line);
  }
  return true;
}

}  // namespace cl1sim
