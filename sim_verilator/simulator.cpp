#include "simulator.h"

#include <algorithm>
#include <iostream>
#include <stdexcept>
#include <utility>

#include <verilated.h>

#include "VCl1Top.h"

namespace cl1sim {

Simulator::Simulator(Options options)
    : options_(std::move(options)),
      memory_(options_),
      random_irq_(options_.random_irq),
      top_(std::make_unique<VCl1Top>()) {
  gpr_shadow_.fill(0);
}

Simulator::~Simulator() = default;

int Simulator::run() {
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

void Simulator::initialize_inputs() {
  top_->clock = 0;
  top_->reset = 1;
  top_->io_ext_irq = 0;
  top_->io_sft_irq = 0;
  top_->io_tmr_irq = 0;
  top_->io_dbg_req_i = 0;
#if defined(CL1_TEST_MODE_BUS)
  top_->io_ibus_req_ready = 0;
  top_->io_ibus_rsp_valid = 0;
  top_->io_ibus_rsp_bits_data = 0;
  top_->io_ibus_rsp_bits_err = 0;
  top_->io_dbus_req_ready = 0;
  top_->io_dbus_rsp_valid = 0;
  top_->io_dbus_rsp_bits_data = 0;
  top_->io_dbus_rsp_bits_err = 0;
#else
  top_->io_master_aw_ready = 0;
  top_->io_master_w_ready = 0;
  top_->io_master_b_valid = 0;
  top_->io_master_b_bits_bresp = 0;
  top_->io_master_b_bits_bid = 0;
  top_->io_master_ar_ready = 0;
  top_->io_master_r_valid = 0;
  top_->io_master_r_bits_rresp = 0;
  top_->io_master_r_bits_rdata = 0;
  top_->io_master_r_bits_rlast = 0;
  top_->io_master_r_bits_rid = 0;
#endif
}

void Simulator::drive_interrupts() {
  IrqSignals irq;
  if (options_.random_irq.any_enabled()) {
    irq = random_irq_.step();
  }
  top_->io_ext_irq = irq.ext ? 1 : 0;
  top_->io_sft_irq = irq.sft ? 1 : 0;
  top_->io_tmr_irq = irq.tmr ? 1 : 0;
}

void Simulator::setup_trace() {
  if (!options_.trace_path) {
    return;
  }
  Verilated::traceEverOn(true);
  trace_ = std::make_unique<VerilatedFstC>();
  top_->trace(trace_.get(), 99);
  trace_->open(options_.trace_path->string().c_str());
}

void Simulator::close_trace() {
  if (trace_) {
    trace_->close();
    trace_.reset();
  }
}

void Simulator::open_commit_log() {
  if (!options_.commit_log_path) {
    return;
  }
  commit_log_ = std::make_unique<std::ofstream>(options_.commit_log_path->string(), std::ios::out | std::ios::trunc);
  if (!commit_log_ || !commit_log_->good()) {
    throw std::runtime_error("failed to open commit log `" + options_.commit_log_path->string() + "`");
  }
}

void Simulator::close_commit_log() {
  if (commit_log_) {
    commit_log_->close();
    commit_log_.reset();
  }
}

void Simulator::emit_commit_log() {
  if (!commit_log_) {
    return;
  }
  (*commit_log_) << "CMT order=" << top_->rvfi_order
                 << " pc=" << hex32(top_->rvfi_pc_rdata)
                 << " insn=" << hex32(top_->rvfi_insn)
                 << " rd=" << std::dec << static_cast<unsigned>(top_->rvfi_rd_addr & kGprIndexMask)
                 << " wdata=" << hex32(top_->rvfi_rd_wdata)
                 << " trap=" << static_cast<unsigned>(top_->rvfi_trap)
                 << "\n";
}

void Simulator::dump_trace() {
  if (trace_) {
    trace_->dump(g_sim_time);
  }
}

void Simulator::reset_core(int cycles) {
  // The generated top expects an active-low external reset because `RST_ACTIVELOW=true`.
  top_->reset = 0;
  for (int i = 0; i < cycles; ++i) {
    tick_internal();
  }
  top_->reset = 1;
  tick_internal();
}

void Simulator::tick(StopInfo& stop) {
  drive_interrupts();
  drive_memory_side();

  top_->clock = 0;
  top_->eval();
  const CycleSnapshot snapshot = sample_cycle_snapshot();
  dump_trace();
  observe_commit(stop);
  observe_trap_stop(stop);
  g_sim_time += 5;

  top_->clock = 1;
  top_->eval();
  dump_trace();
  g_sim_time += 5;

  log_activity(snapshot);
  observe_commit(stop);
  observe_trap_stop(stop);
  complete_memory_handshakes(snapshot, stop);
  ++cycle_count_;
}

CycleSnapshot Simulator::tick_internal() {
  drive_interrupts();
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

void Simulator::drive_memory_side() {
#if defined(CL1_TEST_MODE_BUS)
  top_->io_ibus_req_ready = ibus_pending_.valid ? 0 : 1;
  top_->io_dbus_req_ready = dbus_pending_.valid ? 0 : 1;

  top_->io_ibus_rsp_valid = ibus_pending_.valid ? 1 : 0;
  top_->io_ibus_rsp_bits_data = ibus_pending_.data;
  top_->io_ibus_rsp_bits_err = ibus_pending_.err ? 1 : 0;

  top_->io_dbus_rsp_valid = dbus_pending_.valid ? 1 : 0;
  top_->io_dbus_rsp_bits_data = dbus_pending_.data;
  top_->io_dbus_rsp_bits_err = dbus_pending_.err ? 1 : 0;
#else
  top_->io_master_ar_ready = (axi_read_.state == AxiReadState::kIdle) ? 1 : 0;
  top_->io_master_r_valid = (axi_read_.state == AxiReadState::kData) ? 1 : 0;
  top_->io_master_r_bits_rdata = axi_read_.data;
  top_->io_master_r_bits_rresp = axi_read_.err ? 1 : 0;
  top_->io_master_r_bits_rlast = (axi_read_.state == AxiReadState::kData && axi_read_.beat == axi_read_.len) ? 1 : 0;
  top_->io_master_r_bits_rid = 0;

  top_->io_master_aw_ready = (axi_write_.state == AxiWriteState::kIdle) ? 1 : 0;
  top_->io_master_w_ready =
      (axi_write_.state == AxiWriteState::kIdle || axi_write_.state == AxiWriteState::kData) ? 1 : 0;
  top_->io_master_b_valid = (axi_write_.state == AxiWriteState::kResp) ? 1 : 0;
  top_->io_master_b_bits_bresp = axi_write_.err ? 1 : 0;
  top_->io_master_b_bits_bid = 0;
#endif
}

CycleSnapshot Simulator::sample_cycle_snapshot() const {
  CycleSnapshot snapshot;

#if defined(CL1_TEST_MODE_BUS)
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
#else
  snapshot.ar_valid = top_->io_master_ar_valid;
  snapshot.r_valid = (axi_read_.state == AxiReadState::kData);
  snapshot.aw_valid = top_->io_master_aw_valid;
  snapshot.w_valid = top_->io_master_w_valid;
  snapshot.b_valid = (axi_write_.state == AxiWriteState::kResp);
  snapshot.r_ready = top_->io_master_r_ready;
  snapshot.b_ready = top_->io_master_b_ready;

  if (snapshot.ar_valid && top_->io_master_ar_ready) {
    snapshot.ar_fire = true;
    snapshot.read_request.addr = top_->io_master_ar_bits_araddr;
    snapshot.read_request.mask = kFullWordMask;
    snapshot.read_request.size = top_->io_master_ar_bits_arsize;
    snapshot.read_request.wen = false;
    snapshot.ar_len = top_->io_master_ar_bits_arlen;
  }

  if (snapshot.aw_valid && top_->io_master_aw_ready) {
    snapshot.aw_fire = true;
    snapshot.write_request.addr = top_->io_master_aw_bits_awaddr;
    snapshot.write_request.size = top_->io_master_aw_bits_awsize;
    snapshot.write_request.wen = true;
    snapshot.aw_len = top_->io_master_aw_bits_awlen;
  }

  if (snapshot.w_valid && top_->io_master_w_ready) {
    snapshot.w_fire = true;
    const uint32_t write_base =
        snapshot.aw_fire ? static_cast<uint32_t>(top_->io_master_aw_bits_awaddr) : axi_write_.addr;
    const uint8_t write_size =
        snapshot.aw_fire ? static_cast<uint8_t>(top_->io_master_aw_bits_awsize) : axi_write_.size;
    const uint8_t write_beat = snapshot.aw_fire ? 0 : axi_write_.beat;
    snapshot.write_request.addr = axi_next_addr(write_base, write_beat, write_size);
    snapshot.write_request.data = top_->io_master_w_bits_wdata;
    snapshot.write_request.mask = top_->io_master_w_bits_wstrb;
    snapshot.write_request.size = write_size;
    snapshot.write_request.wen = true;
    snapshot.w_beat = write_beat;
  }

  snapshot.r_fire = snapshot.r_valid && snapshot.r_ready;
  snapshot.b_fire = snapshot.b_valid && snapshot.b_ready;
  snapshot.r_beat = axi_read_.beat;
#endif
  return snapshot;
}

uint32_t Simulator::axi_next_addr(uint32_t base, uint8_t beat, uint8_t size) {
  const uint32_t bytes = 1u << std::min<uint8_t>(size, kMaxAxiTransferSizeLog2);
  return base + static_cast<uint32_t>(beat) * bytes;
}

void Simulator::prepare_axi_read_beat(StopInfo& stop) {
#if defined(CL1_TEST_MODE_CACHE)
  if (axi_read_.state != AxiReadState::kData) {
    return;
  }
  BusRequest request;
  request.addr = axi_next_addr(axi_read_.addr, axi_read_.beat, axi_read_.size);
  request.mask = kFullWordMask;
  request.size = axi_read_.size;
  request.wen = false;
  const PendingResponse response = memory_.handle_request(request, false, stop);
  axi_read_.data = response.data;
  axi_read_.err = response.err;
#else
  (void)stop;
#endif
}

void Simulator::complete_memory_handshakes(const CycleSnapshot& snapshot, StopInfo& stop) {
#if defined(CL1_TEST_MODE_BUS)
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
#else
  if (snapshot.r_fire) {
    if (axi_read_.beat == axi_read_.len) {
      axi_read_ = AxiReadContext{};
    } else {
      ++axi_read_.beat;
      if (!stop.stopped()) {
        prepare_axi_read_beat(stop);
      }
    }
  }

  if (snapshot.b_fire) {
    axi_write_ = AxiWriteContext{};
  }

  if (!stop.stopped() && snapshot.ar_fire) {
    axi_read_.state = AxiReadState::kData;
    axi_read_.addr = snapshot.read_request.addr;
    axi_read_.len = snapshot.ar_len;
    axi_read_.beat = 0;
    axi_read_.size = snapshot.read_request.size;
    axi_read_.err = false;
    prepare_axi_read_beat(stop);
  }

  if (!stop.stopped() && snapshot.aw_fire && !snapshot.w_fire) {
    axi_write_.state = AxiWriteState::kData;
    axi_write_.addr = snapshot.write_request.addr;
    axi_write_.len = snapshot.aw_len;
    axi_write_.beat = 0;
    axi_write_.size = snapshot.write_request.size;
    axi_write_.err = false;
  }

  if (!stop.stopped() && snapshot.w_fire) {
    const PendingResponse response = memory_.handle_request(snapshot.write_request, false, stop);
    axi_write_.err = axi_write_.err || response.err;
    const uint8_t write_len = snapshot.aw_fire ? snapshot.aw_len : axi_write_.len;
    const bool last = top_->io_master_w_bits_wlast || snapshot.w_beat == write_len;
    if (last) {
      axi_write_.state = AxiWriteState::kResp;
    } else {
      axi_write_.state = AxiWriteState::kData;
      axi_write_.addr = snapshot.aw_fire ? static_cast<uint32_t>(top_->io_master_aw_bits_awaddr) : axi_write_.addr;
      axi_write_.len = write_len;
      axi_write_.beat = snapshot.w_beat + 1;
      axi_write_.size = snapshot.write_request.size;
    }
  }
#endif
}

void Simulator::observe_commit(StopInfo& stop) {
  if (stop.stopped()) {
    return;
  }
  if (!top_->rvfi_valid) {
    return;
  }
  const uint64_t order = top_->rvfi_order;
  if (last_commit_order_ && *last_commit_order_ == order) {
    return;
  }
  last_commit_order_ = order;

  emit_commit_log();

  const uint32_t rd = top_->rvfi_rd_addr & kGprIndexMask;
  if (rd != 0) {
    gpr_shadow_[rd] = top_->rvfi_rd_wdata;
  }
  gpr_shadow_[0] = 0;

  memory_.observe_committed_write(
      top_->rvfi_mem_addr,
      top_->rvfi_mem_wdata,
      static_cast<uint8_t>(top_->rvfi_mem_wmask & kFullWordMask),
      stop);
  if (stop.stopped()) {
    return;
  }

  const uint32_t insn = top_->rvfi_insn;
  if (insn == kEbreakInsn || insn == kCompressedEbreakInsn) {
    set_ebreak_stop(stop, "ebreak");
  }
}

void Simulator::observe_trap_stop(StopInfo& stop) {
  if (stop.stopped()) {
    return;
  }

  if (top_->rvfi_pc_wdata != kTrapVectorAddress) {
    return;
  }

  uint32_t word = 0;
  if (!memory_.peek_word(top_->rvfi_pc_rdata, word)) {
    return;
  }

  const bool is_ebreak32 = word == kEbreakInsn;
  const uint32_t halfword_shift = (top_->rvfi_pc_rdata & 0x2u) ? kByteBits * 2 : 0;
  const uint16_t halfword = static_cast<uint16_t>((word >> halfword_shift) & kHalfwordMask);
  const bool is_ebreak16 = halfword == static_cast<uint16_t>(kCompressedEbreakInsn);
  if (!is_ebreak32 && !is_ebreak16) {
    return;
  }

  set_ebreak_stop(stop, "ebreak trap");
}

void Simulator::set_ebreak_stop(StopInfo& stop, const std::string& label) const {
  const uint32_t exit_code = gpr_shadow_[kA0Register];
  stop.kind = (exit_code == 0u) ? StopKind::kPass : StopKind::kFail;
  stop.code = exit_code;
  stop.reason = label + " at pc=" + hex32(top_->rvfi_pc_rdata) + " with a0=" + hex32(exit_code);
}

void Simulator::log_activity(const CycleSnapshot& snapshot) const {
  if (!options_.verbose) {
    return;
  }
  const bool interesting =
#if defined(CL1_TEST_MODE_BUS)
      snapshot.ibus_req_fire || snapshot.ibus_rsp_fire || snapshot.dbus_req_fire || snapshot.dbus_rsp_fire ||
#else
      snapshot.ar_fire || snapshot.r_fire || snapshot.aw_fire || snapshot.w_fire || snapshot.b_fire ||
#endif
      top_->rvfi_valid || cycle_count_ < 8;
  if (!interesting) {
    return;
  }

  std::cerr << "[sim][cycle " << cycle_count_ << "] "
            << "rst=" << static_cast<int>(top_->reset)
            << " irq(ext=" << static_cast<int>(top_->io_ext_irq)
            << " sft=" << static_cast<int>(top_->io_sft_irq)
            << " tmr=" << static_cast<int>(top_->io_tmr_irq)
            << ")";
#if defined(CL1_TEST_MODE_BUS)
  std::cerr
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
            << ")";
#else
  std::cerr
            << " axi(ar_v=" << static_cast<int>(snapshot.ar_valid)
            << " ar_r=" << static_cast<int>(top_->io_master_ar_ready)
            << " ar_addr=" << hex32(top_->io_master_ar_bits_araddr)
            << " r_v=" << static_cast<int>(snapshot.r_valid)
            << " r_r=" << static_cast<int>(snapshot.r_ready)
            << " aw_v=" << static_cast<int>(snapshot.aw_valid)
            << " aw_r=" << static_cast<int>(top_->io_master_aw_ready)
            << " aw_addr=" << hex32(top_->io_master_aw_bits_awaddr)
            << " w_v=" << static_cast<int>(snapshot.w_valid)
            << " w_r=" << static_cast<int>(top_->io_master_w_ready)
            << " b_v=" << static_cast<int>(snapshot.b_valid)
            << " b_r=" << static_cast<int>(snapshot.b_ready)
            << ")";
#endif
  std::cerr << " rvfi_v=" << static_cast<int>(top_->rvfi_valid)
            << " pc_r=" << hex32(top_->rvfi_pc_rdata)
            << " pc_w=" << hex32(top_->rvfi_pc_wdata)
            << "\n";
}

void Simulator::report(const StopInfo& stop) const {
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

int Simulator::exit_code(const StopInfo& stop) {
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

}  // namespace cl1sim
