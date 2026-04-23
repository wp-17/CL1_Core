// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import chisel3.util.circt.ClockGate
import cl1.Cl1Config._
import chisel3.util.experimental.BoringUtils

class diff extends Bundle {
  val commit  = Output(Bool())
  val insn    = Output(UInt(32.W))
  val pc      = Output(UInt(32.W))
  val mode    = Output(UInt(32.W))
  val rd_addr = Output(UInt(5.W))
  val rd_wdata = Output(UInt(32.W))
}

class Cl1Top extends Module{
  val io = IO(new Bundle {
    val ext_irq   = Input(Bool())
    val sft_irq   = Input(Bool())
    val tmr_irq   = Input(Bool())
    val dbg_req_i = Input(Bool())
    val diff_o    = if(SOC_DIFF) Some(new diff) else None
    val master    = if(!EXPOSE_CORE_BUS) Some(new AXI4(BUS_WIDTH, if (SOC_D64) 64 else BUS_WIDTH, 5)) else None
    val ibus      = if(EXPOSE_CORE_BUS)  Some(new CoreBus) else None  // instruction fetch bus
    val dbus      = if(EXPOSE_CORE_BUS)  Some(new CoreBus) else None  // data access bus
  })

  val rvfi      = if(FORMAL_VERIF) Some(FlatIO(new RVFI)) else None

  val rst0  = if(RST_ACTIVELOW) !reset.asBool else reset
  val rst1  = if(RST_ASYNC)     rst0.asAsyncReset else rst0

  val core = if(CKG_EN) {
    val core_wfi    = Wire(Bool())
    val core_clk_en = ~core_wfi
    val gatedClock  = ClockGate(clock, core_clk_en).suggestName(s"clk_gate")
    val core_u      = withClockAndReset(gatedClock, rst1) {
      Module(new Cl1Core)
    }
    core_wfi       := core_u.io.core_wfi
    core_u
  } else {
      withReset(rst1) {Module(new Cl1Core)}
  }
  

  core.io.dbg_req_i := io.dbg_req_i
  core.io.ext_irq   := io.ext_irq
  core.io.sft_irq   := io.sft_irq
  core.io.tmr_irq   := io.tmr_irq

  if(EXPOSE_CORE_BUS) {
    io.ibus.get <> core.io.ibus.get
    io.dbus.get <> core.io.dbus.get
  } else {
    val core_axi = if(SOC_D64) {
      val AXIWidthC = withReset(rst1) {Module(new AXIWidthConverter)}
      core.io.master.get <> AXIWidthC.io.in
      AXIWidthC.io.out
    } else {
      core.io.master.get
    }
    io.master.get <> core_axi
  }


if(SOC_DIFF) {

  val wb_pc     = BoringUtils.bore(core.wbStage.wb_pc)
  val wb_is_c   = BoringUtils.bore(core.wbStage.pplIn.isCInst)
  val wb_cinst  = BoringUtils.bore(core.wbStage.io.cInst)
  val wb_inst   = BoringUtils.bore(core.wbStage.io.inst)
  val rd_idx    = BoringUtils.bore(core.wbStage.rd_idx)
  val rd_wdata  = BoringUtils.bore(core.wbStage.io.wdata)

  val wb_ecall  = BoringUtils.bore(core.wbStage.isValidEcall)
  val rd_wen    = BoringUtils.bore(core.wbStage.io.wen)
  val wb_valid  = BoringUtils.bore(core.wbStage.wb_valid)
  val wb_flush  = BoringUtils.bore(core.wbStage.io.flush)

  val diff_port = io.diff_o.get 
  diff_port.commit   := wb_valid & !wb_flush & rd_wen || wb_ecall
  diff_port.insn     := Mux(wb_is_c, wb_cinst, wb_inst)
  diff_port.pc       := wb_pc
  diff_port.mode     := 3.U
  diff_port.rd_addr  := rd_idx
  diff_port.rd_wdata := rd_wdata
}

if(difftest) {
  val wb_pc     = BoringUtils.bore(core.wbStage.wb_pc)
  val wb_is_c   = BoringUtils.bore(core.wbStage.pplIn.isCInst)
  val wb_cinst  = BoringUtils.bore(core.wbStage.io.cInst)
  val wb_inst   = BoringUtils.bore(core.wbStage.io.inst)
  val rd_idx    = BoringUtils.bore(core.wbStage.rd_idx)
  val rd_wdata  = BoringUtils.bore(core.wbStage.io.wdata)

  val wb_ecall  = BoringUtils.bore(core.wbStage.isValidEcall)
  val rd_wen    = BoringUtils.bore(core.wbStage.io.wen)
  val wb_valid  = BoringUtils.bore(core.wbStage.wb_valid)
  val wb_flush  = BoringUtils.bore(core.wbStage.io.flush)
}

if(FORMAL_VERIF && WB_PIPESTAGE) { withReset(rst1) {
  val rvfi_port = rvfi.get
  val wb_valid  = BoringUtils.bore(core.wbStage.wb_valid)
  val wb_flush  = BoringUtils.bore(core.wbStage.io.flush)
  val wb_ecall  = BoringUtils.bore(core.wbStage.isValidEcall)
  val wb_cmt    = BoringUtils.bore(core.wbStage.wb_commit)
  val wb_diff_cmt = BoringUtils.bore(core.wbStage.diff_commit)
  val trap      = BoringUtils.bore(core.excp.excp_take_en)

  // Retire through RVFI when the instruction either commits normally, or
  // takes an M-mode trap (ECALL, or EBREAK with dcsr.ebreakm==0).
  val rvfi_valid = wb_diff_cmt || trap
  val valid_cnt = Wire(UInt(64.W))
  val wb_pc     = BoringUtils.bore(core.wbStage.wb_pc)
  val wb_is_c   = BoringUtils.bore(core.wbStage.pplIn.isCInst)
  val wb_cinst  = BoringUtils.bore(core.wbStage.io.cInst)
  val wb_inst   = BoringUtils.bore(core.wbStage.io.inst)
  valid_cnt := RegEnable((valid_cnt + 1.U), 0.U, rvfi_valid)

  val dx_rs1_addr = BoringUtils.bore(core.idStage.io.rs1Addr)
  val dx_rs2_addr = BoringUtils.bore(core.idStage.io.rs2Addr)
  val dx_rs1_rdata = BoringUtils.bore(core.idStage.io.rs1Value)
  val dx_rs2_rdata = BoringUtils.bore(core.idStage.io.rs2Value)
  val dxwb_hsked  = BoringUtils.bore(core.idStage.io.pplOut.valid) && BoringUtils.bore(core.wbStage.io.pplIn.ready)
  val (wb_rs1_addr, wb_rs2_addr, wb_rs1_rdata, wb_rs2_rdata) = (
    RegEnable(dx_rs1_addr, 0.U, dxwb_hsked),
    RegEnable(dx_rs2_addr, 0.U, dxwb_hsked),
    RegEnable(dx_rs1_rdata, 0.U, dxwb_hsked),
    RegEnable(dx_rs2_rdata, 0.U, dxwb_hsked)
  )

  val wb_rd_wen  = BoringUtils.bore(core.wbStage.io.wen)
  val wb_rd_addr = BoringUtils.bore(core.wbStage.io.rd_idx)
  val wb_rd_wdata = BoringUtils.bore(core.wbStage.io.wdata)

  val dx_valid    = BoringUtils.bore(core.idStage.dx_valid)
  val dx_pc       = BoringUtils.bore(core.idStage.pc)
  val f2_valid    = BoringUtils.bore(core.ifStage.ir_vld_r)
  val f2_pc       = BoringUtils.bore(core.ifStage.pc_r)
  val f1_pc       = BoringUtils.bore(core.ifStage.pc_n)

  val dx_mem_req     = BoringUtils.bore(core.lsu.io.out.req.valid)
  val dx_mem_addr    = BoringUtils.bore(core.lsu.io.out.req.bits.addr)
  val dx_mem_wen     = BoringUtils.bore(core.lsu.io.out.req.bits.wen)
  val dx_mem_wdata   = BoringUtils.bore(core.lsu.io.out.req.bits.data)
  val dx_mem_mask    = BoringUtils.bore(core.lsu.io.out.req.bits.mask)
  val wb_mem_rdata   = BoringUtils.bore(core.lsu.io.out.rsp.bits.data)

  val mem_req_hsked  = BoringUtils.bore(core.lsu.io.out.req.valid) && BoringUtils.bore(core.lsu.io.out.req.ready)
  val mem_rsp_hsked  = BoringUtils.bore(core.lsu.io.out.rsp.valid) && BoringUtils.bore(core.lsu.io.out.rsp.ready)

  val mem_addr_n     = Mux(dx_mem_req, dx_mem_addr, 0.U)
  val mem_rmask_n    = Mux(dx_mem_req & ~dx_mem_wen, dx_mem_mask, 0.U)
  val mem_wmask_n    = Mux(dx_mem_req & dx_mem_wen, dx_mem_mask, 0.U)
  val mem_wdata_n    = Mux(dx_mem_req & dx_mem_wen, dx_mem_wdata, 0.U)

  val mem_addr       = RegEnable(mem_addr_n, 0.U, mem_req_hsked)
  val mem_rmask      = RegEnable(mem_rmask_n, 0.U, mem_req_hsked)
  val mem_wmask      = RegEnable(mem_wmask_n, 0.U, mem_req_hsked)
  val mem_wdata      = RegEnable(mem_wdata_n, 0.U, mem_req_hsked)

  rvfi_port.rvfi_valid     := rvfi_valid
  rvfi_port.rvfi_order     := valid_cnt
  rvfi_port.rvfi_insn      := Mux(wb_is_c, wb_cinst, wb_inst)
  rvfi_port.rvfi_trap      := false.B   //no illegal instruction or misaligned fetch / load / store now
  rvfi_port.rvfi_halt      := false.B

  val intr_taken = BoringUtils.bore(core.excp.irq_take_en)
  val excp_flush_pc   = BoringUtils.bore(core.excp.flush_pc)
  val excp_flush_ofst = BoringUtils.bore(core.excp.flush_ofst)
  val mret_taken      = BoringUtils.bore(core.excp.cmt_mret_en)
  val trap_target_pc  = excp_flush_pc + excp_flush_ofst

  val dbg_flush  = BoringUtils.bore(core.dm.io.dbg_flush)
  chisel3.assume(!dbg_flush)

  val intr_pending = RegInit(false.B)
  when (trap || intr_taken) {
    intr_pending := true.B
  } .elsewhen (rvfi_valid) {
    intr_pending := false.B
  }

  rvfi_port.rvfi_intr      := intr_pending && rvfi_valid
  rvfi_port.rvfi_mode      := "b11".U(2.W)
  rvfi_port.rvfi_ixl       := "b01".U(2.W)

  rvfi_port.rvfi_rs1_addr  := wb_rs1_addr
  rvfi_port.rvfi_rs2_addr  := wb_rs2_addr
  rvfi_port.rvfi_rs1_rdata := wb_rs1_rdata
  rvfi_port.rvfi_rs2_rdata := wb_rs2_rdata
  rvfi_port.rvfi_rd_addr   := Mux(wb_rd_wen, wb_rd_addr, 0.U)
  rvfi_port.rvfi_rd_wdata  := Mux(wb_rd_wen, wb_rd_wdata, 0.U)
  rvfi_port.rvfi_pc_rdata  := wb_pc
  rvfi_port.rvfi_pc_wdata  := Mux(trap || mret_taken, trap_target_pc,
                                  Mux(dx_valid, dx_pc, f2_pc))

  rvfi_port.rvfi_mem_addr  := Mux(mem_rsp_hsked, mem_addr, 0.U)
  rvfi_port.rvfi_mem_rmask := Mux(mem_rsp_hsked, mem_rmask, 0.U)
  rvfi_port.rvfi_mem_wmask := Mux(mem_rsp_hsked, mem_wmask, 0.U)
  rvfi_port.rvfi_mem_rdata := Mux(mem_rsp_hsked, wb_mem_rdata, 0.U)
  rvfi_port.rvfi_mem_wdata := Mux(mem_rsp_hsked, mem_wdata, 0.U)

  // ---- CSR channels ----
  val wb_csr_idx   = BoringUtils.bore(core.wbStage.io.csr_idx)
  val wb_csr_wen   = BoringUtils.bore(core.wbStage.io.csrWen)
  val wb_csr_wdata = BoringUtils.bore(core.wbStage.io.csrWdat)
  val wb_csr_rdata = BoringUtils.bore(core.wbStage.pplIn.rdWdat)

  val wb_is_csr_insn = (wb_inst(6,0) === "b1110011".U) && (wb_inst(14,12) =/= 0.U)

  val cur_mstatus  = BoringUtils.bore(core.csr.mstatus)
  val cur_mie      = BoringUtils.bore(core.csr.mie)
  val cur_mip      = BoringUtils.bore(core.csr.mip)
  val cur_mepc     = BoringUtils.bore(core.csr.mepc)
  val cur_mcause   = BoringUtils.bore(core.csr.mcause)
  val cur_mtvec    = BoringUtils.bore(core.csr.mtvec)
  val cur_mscratch = BoringUtils.bore(core.csr.mscratch)
  val cur_misa     = BoringUtils.bore(core.csr.misa)

  val cmt_epc_en    = BoringUtils.bore(core.excp.cmt_epc_en)
  val cmt_cause_en  = BoringUtils.bore(core.excp.cmt_cause_en)
  val cmt_status_en = BoringUtils.bore(core.excp.cmt_status_en)
  val cmt_mret_en   = BoringUtils.bore(core.excp.cmt_mret_en)
  val cmt_epc_n     = BoringUtils.bore(core.excp.cmt_epc_n)
  val cmt_cause_n   = BoringUtils.bore(core.excp.cmt_cause_n)

  val cur_mstatus_mie  = cur_mstatus(3)
  val cur_mstatus_mpie = cur_mstatus(7)

  val mstatus_trap_wdata = {
    val cur = cur_mstatus
    val withClrMie  = Cat(cur(31, 4), 0.U(1.W), cur(2, 0))                                   // MIE=0
    val withMpieMie = Cat(withClrMie(31, 8), cur_mstatus_mie, withClrMie(6, 0))              // MPIE<-MIE
    withMpieMie
  }
  val mstatus_mret_wdata = {
    val cur = cur_mstatus
    val withSetMie  = Cat(cur(31, 4), cur_mstatus_mpie, cur(2, 0))                           // MIE<-MPIE
    val withSetMpie = Cat(withSetMie(31, 8), 1.U(1.W), withSetMie(6, 0))                     // MPIE=1
    withSetMpie
  }
  val mstatus_implicit_wdata = Mux(cmt_status_en, mstatus_trap_wdata, mstatus_mret_wdata)

  def driveCsr(chan: RVFICSR, addr: UInt, cur: UInt,
               implicitWrite: Bool = false.B,
               implicitWdata: UInt = 0.U): Unit = {
    val selCsrInsn = wb_is_csr_insn && (wb_csr_idx === addr) && rvfi_valid
    val selW       = wb_csr_wen    && (wb_csr_idx === addr) && rvfi_valid
    val implW      = implicitWrite && rvfi_valid
    val anyW       = selW || implW
    chan.rmask := Fill(32, selCsrInsn)
    chan.wmask := Fill(32, anyW)
    chan.rdata := cur
    chan.wdata := Mux(selW, wb_csr_wdata, Mux(implW, implicitWdata, cur))
  }

  driveCsr(rvfi_port.rvfi_csr_mstatus,  CSRs.mstatus,  cur_mstatus,
           cmt_status_en || cmt_mret_en, mstatus_implicit_wdata)
  driveCsr(rvfi_port.rvfi_csr_mie,      CSRs.mie,      cur_mie)
  driveCsr(rvfi_port.rvfi_csr_mip,      CSRs.mip,      cur_mip)
  driveCsr(rvfi_port.rvfi_csr_mepc,     CSRs.mepc,     cur_mepc,
           cmt_epc_en, Cat(cmt_epc_n(31, 1), 0.U(1.W)))
  driveCsr(rvfi_port.rvfi_csr_mcause,   CSRs.mcause,   cur_mcause,
           cmt_cause_en, cmt_cause_n)
  driveCsr(rvfi_port.rvfi_csr_mtvec,    CSRs.mtvec,    cur_mtvec)
  driveCsr(rvfi_port.rvfi_csr_mscratch, CSRs.mscratch, cur_mscratch)
  driveCsr(rvfi_port.rvfi_csr_misa,     CSRs.misa,     cur_misa)
}}

}
