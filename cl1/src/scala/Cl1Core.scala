// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import cl1.Cl1Config._
import cl1.Cl1PowerSaveConfig._
import utils._
import chisel3.util.circt.ClockGate
import chisel3.util.experimental.BoringUtils

class Cl1Core extends Module {
  val io = IO(new Bundle {
    val dbg_req_i = Input(Bool())
    val ext_irq   = Input(Bool())
    val sft_irq   = Input(Bool())
    val tmr_irq   = Input(Bool())
    val core_wfi  = Output(Bool())
    val master    = if(!EXPOSE_CORE_BUS) Some(new AXI4(BUS_WIDTH, BUS_WIDTH, 5)) else None
    val ibus      = if(EXPOSE_CORE_BUS)  Some(new CoreBus)  else None  // instruction fetch bus
    val dbus      = if(EXPOSE_CORE_BUS)  Some(new CoreBus)  else None  // data access bus
  })


//TODO: This wiring approach is not significantly different from Verilog
  val ifStage = Module(new Cl1IFStage())

  val bpu     = Module(new Cl1BPU())

  val aligner = Module(new FetchAlign())

  val idStage = Module(new Cl1IDEXStage())

  val csr     = Module(new Cl1CSR())

  val gpr     = Module(new Cl1RegFile())

  val lsu     = Module(new Cl1LSU())

  val wbStage = Module(new Cl1WBStage())

  val dm      = Module(new Cl1DM)

  val excp    = Module(new Cl1EXCP)

  dm.io.dbg_external_req_i := io.dbg_req_i

  val pipe_flush = excp.io.flush || dm.io.dbg_flush

  // ifStage.io.addrNotSeq := bpu.io.addrNotSeq //TODO: move this signal into IFU
  // ifStage.io.nextFetchAddr := pcGen.io.nextAddr
  ifStage.io.pplOut <> idStage.io.pplIn
  idStage.io.toifu  <> ifStage.io.fromdxu
  ifStage.io.toaligner <> aligner.io.fromifu
  aligner.io.toifu <> ifStage.io.fromaligner

  ifStage.io.flush := pipe_flush
  ifStage.io.flush_pc := Mux(dm.io.dbg_flush, dm.io.dbg_flush_pc, excp.io.flush_pc)
  ifStage.io.flush_pc_ofst := Mux(dm.io.dbg_flush, 0.U, excp.io.flush_ofst)
  ifStage.io.ifu_halt := excp.io.ifu_halt
  excp.io.ifu_halt_ack    := ifStage.io.ifu_halt_ack

  ifStage.io.toBpu <> bpu.io.fromIfu
  bpu.io.x1_val    := gpr.io.x1_val
  bpu.io.jalr_rs1_x1_dep := idStage.io.wen_x1 | wbStage.io.wen_x1

  val rs1 = idStage.io.rs1Addr
  val rs2 = idStage.io.rs2Addr
  val rd  = wbStage.io.rd_idx
  val readCSR = idStage.io.csrAddr
  val writeCSR = wbStage.io.csr_idx
  val wen   = wbStage.io.pplIn.bits.wen
  val csrWen = wbStage.io.csrWen
  val bypass = wbStage.io.forwardDat

  //rd comes from WBStage
  val rs1Hazard = idStage.io.valid && idStage.io.rs1_ren && (rs1 === rd) && wen && wbStage.io.valid
  val rs2Hazard = idStage.io.valid && idStage.io.rs2_ren && (rs2 === rd) && wen && wbStage.io.valid
  //stall when writing to CSR, trap ?
  val csrHazard = idStage.io.valid && readCSR.orR && csrWen && wbStage.io.valid

  val dx_rs1dat  = if(WB_PIPESTAGE)  Mux(rs1Hazard, bypass, gpr.io.readDataA) else gpr.io.readDataA
  val dx_rs2dat  = if(WB_PIPESTAGE)  Mux(rs2Hazard, bypass, gpr.io.readDataB) else gpr.io.readDataB
  val dx_stall   = if(WB_PIPESTAGE)  csrHazard || (rs1Hazard || rs2Hazard) & wbStage.io.is_mem_load || excp.io.dxu_halt else false.B

  idStage.io.pplOut <> wbStage.io.pplIn
  idStage.io.rs1Value := dx_rs1dat
  idStage.io.rs2Value := dx_rs2dat

  idStage.io.csrData  := csr.io.rdValue
  idStage.io.stall    := dx_stall
  idStage.io.flush    := pipe_flush
  excp.io.dxu_halt_ack := idStage.io.dxu_halt_ack

  csr.io.rdAddr := readCSR
  csr.io.wrAddr := writeCSR
  csr.io.wen    := csrWen

  csr.io.instr  := wbStage.io.inst
  csr.io.c_instr := wbStage.io.cInst
  csr.io.wrValue := wbStage.io.csrWdat

  dm.io.dbg2csr <> csr.io.dbg_intf

  gpr.io.readAddrA := idStage.io.rs1Addr
  gpr.io.readAddrB := idStage.io.rs2Addr
  gpr.io.wen       := wbStage.io.wen
  gpr.io.writeAddr := rd
  gpr.io.writeData := wbStage.io.wdata

  wbStage.io.dbg <> dm.io.wb2dbg

  lsu.io.flush :=  pipe_flush && wbStage.io.valid
  lsu.io.in.req  <> idStage.io.mem
  lsu.io.in.resp <> wbStage.io.mem

  if(WB_PIPESTAGE) {
    PipelineConnect(idStage.io.pplOut, wbStage.io.pplIn, false.B)
  } else {
    wbStage.io.pplIn <> idStage.io.pplOut
  }

  wbStage.io.flush := pipe_flush
  wbStage.io.toExcp <> excp.io.wb2Excp

  excp.io.dbg2excp  <> dm.io.dbg2excp
  if (cl1.Cl1Config.FORMAL_VERIF) {
    // Under formal verification, force debug-mode-related signals into
    // architectural mode so traps go to mtvec (not the debug exception base).
    // BMC otherwise picks anyinit values for dm registers and explores
    // debug-mode trap dispatch, which is outside the RVFI ISA model.
    excp.io.dbg2excp.debug_mode     := false.B
    excp.io.dbg2excp.debug_irq_mask := false.B
    excp.io.dbg2excp.debug_take_req := false.B
    excp.io.dbg2excp.ebrk_excp_en   := dm.io.dbg2excp.ebrk_excp_en
  }
  excp.io.excp2Csr  <> csr.io.excp_intf

  excp.io.ext_irq := io.ext_irq
  excp.io.sft_irq := io.sft_irq
  excp.io.tmr_irq := io.tmr_irq
  io.core_wfi     := excp.io.core_wfi

/*
  if(difftest) {
    val wb_info = wbStage.io.spkDiffIo.get
    val reqBuffer = RegInit(0.U.asTypeOf((new CoreBus).req.bits))
    val reqBufferEnable = RegInit(false.B)
    when(lsu.io.out.req.fire) {
      reqBuffer := lsu.io.out.req.bits
      reqBufferEnable := true.B
    }.elsewhen(lsu.io.out.rsp.fire) {
      reqBufferEnable := false.B
    }

    val difftest = Module(new Difftest())
    difftest.io.skip  := reqBufferEnable && (reqBuffer.addr(31) =/= true.B) // skip uncacheable request
    difftest.io.commit := wb_info.commit
    difftest.io.c_inst := wb_info.cInst
    difftest.io.inst := wb_info.inst
    difftest.io.pc := wb_info.pc
    difftest.io.is_c_inst := wb_info.isCInst
    difftest.io.clock := clock
    difftest.io.reset := reset
  }
*/

  if(EXPOSE_CORE_BUS) {
    // Directly expose CoreBus interfaces, bypass cache/xbar/AXI
    io.ibus.get <> aligner.io.bus
    io.dbus.get <> lsu.io.out
    excp.io.icache_idle := true.B
    excp.io.dcache_idle := true.B
    idStage.io.icache_req.ready := true.B
    idStage.io.dcache_req.ready := true.B
  } else {
    val xbar    = Module(new crossbarCache())

    if(HAS_ICACHE) {
      val icache = Module(new Cl1ICACHE)
      aligner.io.bus <> icache.io.in
      excp.io.icache_idle := icache.io.icache_idle
      idStage.io.icache_req <> icache.io.dxReq
      xbar.io.in(0) <> icache.io.out
    } else {
      val ibridge = Module(new CoreBus2CacheBus)
      aligner.io.bus <> ibridge.io.in
      excp.io.icache_idle := true.B
      idStage.io.icache_req.ready := true.B
      xbar.io.in(0) <> ibridge.io.out
    }

    if(HAS_DCACHE) {
      val dcache  = Module(new Cl1DCACHE)
      lsu.io.out     <> dcache.io.in
      excp.io.dcache_idle := dcache.io.dcache_idle
      idStage.io.dcache_req <> dcache.io.dxReq
      xbar.io.in(1) <> dcache.io.out
        if(DCACHE_CKG_EN) {
          val dcache_ck_en = BoringUtils.bore(dcache.dcache_ck_en)
          val dcache_clk   = ClockGate(clock, dcache_ck_en)
          dcache.clock := dcache_clk
        }
    } else {
      val dbridge = Module(new CoreBus2CacheBus)
      BypReg(lsu.io.out.req)     <> dbridge.io.in.req
      lsu.io.out.rsp     <> dbridge.io.in.rsp
      excp.io.dcache_idle := true.B
      idStage.io.dcache_req.ready := true.B
      xbar.io.in(1) <> dbridge.io.out
    }

    io.master.get <> xbar.io.out
  }

  if(LSU_CKG_EN) {
    val lsu_ck_en = BoringUtils.bore(lsu.lsu_ck_en)
    val lsu_clk   = ClockGate(clock, lsu_ck_en)
    lsu.clock := lsu_clk
  }

}
