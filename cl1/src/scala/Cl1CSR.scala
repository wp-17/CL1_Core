package cl1

import chisel3._
import chisel3.util._
import Cl1Config.TVEC_ADDR

object CSRs {
  val misa    = 0x301.U(12.W)
  val mstatus = 0x300.U(12.W)
  val mtvec = 0x305.U(12.W)
  val mscratch = 0x340.U(12.W)
  val mepc = 0x341.U(12.W)
  val mcause = 0x342.U(12.W)
  val dcsr   = 0x7b0.U(12.W)
  val dpc    = 0x7b1.U(12.W)
  val dscratch0 = 0x7b2.U(12.W)
  val dscratch1 = 0x7b3.U(12.W)
  val mip    = 0x344.U(12.W)
  val mie    = 0x304.U(12.W)
}


class CSRIO() extends Bundle {
  val rdAddr = Input(UInt(12.W))
  val rdValue = Output(UInt(32.W))
  val wrAddr = Input(UInt(12.W))
  val wrValue = Input(UInt(32.W))
  val wen    = Input(Bool())
  
  val instr = Input(UInt(32.W))
  val c_instr = Input(UInt(16.W))

  val dbg_intf = Flipped(new Dbg2CsrSignal)
  val excp_intf = Flipped(new excp2Csr)
}


// TODO: Treating CSR as GPR here is incorrect and needs to be fixed.
// TODO: Implement U mode and M mode
class Cl1CSR() extends Module {
  val io = IO(new CSRIO())

  val csr_waddr = io.wrAddr
  val csr_raddr = io.rdAddr
  val csr_wen  = io.wen
  val csr_ren  = true.B
  val csr_wdat = io.wrValue

  // from debug module
  val dbg = io.dbg_intf
  val dbg_csr_wen   = dbg.csr_update_en
  val dpc_update    = dbg.dpc_update
  val dbg_cause     = dbg.dbg_cause

  val ext_irq     = io.excp_intf.ext_irq
  val sft_irq     = io.excp_intf.sft_irq
  val tmr_irq     = io.excp_intf.tmr_irq

  val cmt_epc_en    = io.excp_intf.cmt_epc_en
  val cmt_epc_n     = io.excp_intf.cmt_epc_n
  val cmt_status_en = io.excp_intf.cmt_status_en
  val cmt_cause_en  = io.excp_intf.cmt_cause_en
  val cmt_cause_n   = io.excp_intf.cmt_cause_n
  val cmt_mret_en   = io.excp_intf.cmt_mret_en

  val wen_dcsr       = Wire(Bool())
  val wen_dpc        = Wire(Bool())
  val wen_dscratch0  = Wire(Bool())
  val wen_dscratch1  = Wire(Bool())
  val wen_mtvec      = Wire(Bool())
  val wen_mepc       = Wire(Bool())
  val wen_mcause     = Wire(Bool())
  val wen_mie        = Wire(Bool())

  class mieBundle extends Bundle {
    val reserved  = UInt(16.W)
    val tiezero14  = UInt(2.W)
    val lcofie    = UInt(1.W)
    val tiezero12  = UInt(1.W)
    val meie      = UInt(1.W)
    val tiezero10  = UInt(1.W)
    val seie      = UInt(1.W)
    val tiezero8  = UInt(1.W)
    val mtie      = UInt(1.W)
    val tiezero6  = UInt(1.W)
    val stie      = UInt(1.W)
    val tiezero4  = UInt(1.W)
    val msie      = UInt(1.W)
    val tiezero2  = UInt(1.W)
    val ssie      = UInt(1.W)
    val tiezero0  = UInt(1.W)
  }

  val lcofip = WireInit(false.B)
  val meip = RegNext(ext_irq, false.B)
  val mtip = RegNext(tmr_irq, false.B)
  val msip = RegNext(sft_irq, false.B)
  val seip = WireInit(false.B)
  val stip = WireInit(false.B)
  val ssip = WireInit(false.B)
  val mip  = Cat(0.U(16.W), 0.U(2.W), lcofip, false.B, meip, false.B, seip, false.B, mtip, false.B, stip, false.B, msip, false.B, ssip, false.B)

  val mie_wdat = csr_wdat.asTypeOf(new mieBundle)

  val meie     = RegEnable(mie_wdat.meie, false.B, wen_mie)
  val mtie     = RegEnable(mie_wdat.mtie, false.B, wen_mie)
  val msie     = RegEnable(mie_wdat.msie, false.B, wen_mie)
  val seie     = WireInit(false.B)
  val stie     = WireInit(false.B)
  val ssie     = WireInit(false.B)
  val lcofie   = WireInit(false.B)
  val mie      = Cat(0.U(16.W), 0.U(2.W), lcofie, false.B, meie, false.B, seie, false.B, mtie, false.B, stie, false.B, msie, false.B, ssie, false.B)

  class mstatusBundle extends Bundle {
    val sd        = UInt(1.W)
    val wpri25    = UInt(6.W)
    val sdt       = UInt(1.W)
    val spelp     = UInt(1.W)
    val tsr       = UInt(1.W)
    val tw        = UInt(1.W)
    val tvm       = UInt(1.W)
    val mxr       = UInt(1.W)
    val sum       = UInt(1.W)
    val mprv      = UInt(1.W)
    val xs        = UInt(2.W)
    val fs        = UInt(2.W)
    val mpp       = UInt(2.W)
    val vs        = UInt(2.W)
    val spp       = UInt(1.W)
    val mpie      = UInt(1.W)
    val ube       = UInt(1.W)
    val spie      = UInt(1.W)
    val wpri4     = UInt(1.W)
    val mie       = UInt(1.W)
    val wpri2     = UInt(1.W)
    val sie       = UInt(1.W)
    val wpri0     = UInt(1.W)
  }

  val csrw_mstatus = Wire(Bool())
  val mstatus_wdat = csr_wdat.asTypeOf(new mstatusBundle)
  val sd          = WireInit(false.B)
  val wpri25      = WireInit(0.U(6.W))
  val sdt         = WireInit(false.B)
  val spelp       = WireInit(false.B)
  val tsr         = WireInit(false.B)
  val tw          = WireInit(false.B)
  val tvm         = WireInit(false.B)
  val mxr         = WireInit(false.B)
  val sum         = WireInit(false.B)
  val mprv        = WireInit(false.B)
  val xs          = WireInit(0.U(2.W))
  val fs          = WireInit(0.U(2.W))
  val vs          = WireInit(0.U(2.W))
  val spp         = WireInit(false.B)
  val ube         = WireInit(false.B)
  val spie        = WireInit(false.B)
  val wpri4       = WireInit(false.B)
  val wpri2       = WireInit(false.B)
  val sie         = WireInit(false.B)
  val wpri0       = WireInit(false.B)

  val wen_mstatus = Wire(Bool())
  // val mpp_en      = cmt_status_en || cmt_mret_en
  // val mpp_n       = MuxCase(0.U, Seq(
  //                   cmt_status_en -> "b11".U,
  //                   cmt_mret_en   -> "b11".U
  // ))
  // val mpp         = RegEnable(mpp_n, 0.U, mpp_en)

  val mpp            = "b11".U

  val mpie        = Wire(Bool())
  val mstatus_mie_en      = csrw_mstatus || cmt_status_en || cmt_mret_en
  val mstatus_mie_n       = MuxCase(0.U, Seq(
                            cmt_status_en -> false.B,
                            cmt_mret_en   -> mpie,
                            csrw_mstatus  -> mstatus_wdat.mie
  ))
  val mstatus_mie         = RegEnable(mstatus_mie_n, false.B, mstatus_mie_en)

  val mpie_n      = MuxCase(false.B,Seq(
                    cmt_status_en -> mstatus_mie,
                    cmt_mret_en   -> true.B,
                    csrw_mstatus  -> mstatus_wdat.mpie
  ))
  val mpie_en     = mstatus_mie_en
  mpie            := RegEnable(mpie_n, false.B, mpie_en)

  val mstatus     = Cat(sd, wpri25, sdt, spelp, tsr, tw, tvm, mxr, sum, mprv, xs,
                        fs, mpp, vs, spp, mpie, ube, spie, wpri4, mstatus_mie, wpri2, sie, wpri0)

  val wen_mscratch = Wire(Bool())
  val mscratch    = RegEnable(csr_wdat, 0.U(32.W), wen_mscratch)

  // debug csrs
  // Debug Control and Status
  class DcsrBundle extends Bundle {
    val xdebugver = UInt(4.W)
    val reserved1 = UInt(12.W)
    val ebreakm   = UInt(1.W)
    val reserved2 = UInt(1.W) 
    val ebreaks   = UInt(1.W)
    val ebreaku   = UInt(1.W)
    val stepie    = UInt(1.W)
    val stopcount = UInt(1.W)
    val stoptime  = UInt(1.W)
    val cause     = UInt(3.W)
    val reserved3 = UInt(1.W)
    val mprven    = UInt(1.W)
    val nmip      = UInt(1.W)
    val step      = UInt(1.W)
    val prv       = UInt(2.W)
  }

  val dcsr_wdat = csr_wdat.asTypeOf(new DcsrBundle)

  val xdebugver = WireDefault(4.U(4.W)) // debug support exists as it is described in this document
  val ebreakm   = RegEnable(dcsr_wdat.ebreakm, 0.U(1.W), wen_dcsr)
  val ebreaks   = RegEnable(dcsr_wdat.ebreaks, 0.U(1.W), wen_dcsr)
  val ebreaku   = RegEnable(dcsr_wdat.ebreaku, 0.U(1.W), wen_dcsr)
  val stepie    = WireDefault(0.U(1.W)) // Interrupts are disabled during single stepping
  val stopcount = WireDefault(0.U(1.W)) // Stop counting when the debug module is halted
  val stoptime  = WireDefault(0.U(1.W)) // Don't incrment any hart-loacal timers while in Debug Mode
  val cause     = RegEnable(dbg_cause, 0.U(3.W), dbg_csr_wen)     // debug cause
  val mprven    = WireDefault(0.U(1.W)) // MPRV in mstatus is ignored
  val nmip      = RegEnable(dcsr_wdat.nmip, 0.U(1.W), wen_dcsr)     // Non-maskable interrupt pending
  val step      = RegEnable(dcsr_wdat.step, 0.U(1.W), wen_dcsr)     // Single step
  val prv       = RegEnable(dcsr_wdat.prv,  0.U(2.W), wen_dcsr)     // privilege level when Debug Mode was entered

  val dcsr      = Cat(xdebugver, 0.U(12.W), ebreakm, 0.U(1.W), ebreaks, ebreaku, stepie, stopcount,
                      stoptime, cause, 0.U(1.W), mprven, nmip, step, prv)

  // Debug PC (dpc, at 0x7b1)
  val dpc_wdat = Mux1H(
    Seq(
      dbg_csr_wen -> dpc_update,
      wen_dpc       -> csr_wdat
    )
  )
  val dpc       = RegEnable(dpc_wdat, 0.U(32.W), wen_dpc | dbg_csr_wen) // Debug PC

  // Debug Scratch (dscratch0, at 0x7b2)
  val dscratch0 = RegEnable(csr_wdat, 0.U(32.W), wen_dscratch0) // Debug Scratch 0

  // Debug Scratch (dscratch1, at 0x7b3)
  val dscratch1 = RegEnable(csr_wdat, 0.U(32.W), wen_dscratch1) // Debug Scratch 1

  // val mstatus   = RegInit("h1800".U(32.W))
  val mtvec     = RegEnable(csr_wdat, TVEC_ADDR.U(32.W), wen_mtvec)

  val mepc_wdata = Mux(cmt_epc_en, cmt_epc_n, csr_wdat)
  val mepc      = RegEnable(mepc_wdata, 0.U(32.W), wen_mepc)
  val mcause_wdata = Mux(cmt_cause_en, cmt_cause_n, csr_wdat)
  val mcause    = RegEnable(cmt_cause_n, 0.U(32.W), wen_mcause)

  val misa      = WireInit("h40000104".U(32.W))


  val allCSRs  = Seq(
    CSRs.misa      -> misa,
    CSRs.mstatus   -> mstatus,
    CSRs.mtvec     -> mtvec,
    CSRs.mscratch  -> mscratch,
    CSRs.mepc      -> mepc, 
    CSRs.mcause    -> mcause,
    CSRs.dcsr      -> dcsr,
    CSRs.dpc       -> dpc,  
    CSRs.dscratch0 -> dscratch0,
    CSRs.dscratch1 -> dscratch1,
    CSRs.mip       -> mip,
    CSRs.mie       -> mie
  )
  val csr_rselOh  = VecInit(allCSRs.map { case (addr, reg)  => csr_raddr === addr})
  val csr_wselOh  = VecInit(allCSRs.map { case (addr, reg)  => csr_waddr === addr})
  val csr_renOh  = csr_rselOh.map(_ && csr_ren)
  val csr_wenOh  = csr_wselOh.map(_ && csr_wen)

  def getCSRIndex(targetAddr: UInt): Int = {
    val index = allCSRs.indexWhere { case (addr, _) => 
      addr.litValue == targetAddr.litValue
    }
    require(index >= 0, s"CSR地址 ${targetAddr.litValue} 未定义")
    index
  }

  def getCSRWen(targetAddr: UInt): Bool = {
    val staticIndex = getCSRIndex(targetAddr)
    csr_wenOh(staticIndex)
  }

  wen_dcsr       := getCSRWen(CSRs.dcsr)
  wen_dpc        := getCSRWen(CSRs.dpc)
  wen_dscratch0  := getCSRWen(CSRs.dscratch0)
  wen_dscratch1  := getCSRWen(CSRs.dscratch1)
  wen_mtvec      := getCSRWen(CSRs.mtvec)  
  wen_mepc       := getCSRWen(CSRs.mepc) || cmt_epc_en
  wen_mcause     := getCSRWen(CSRs.mcause) || cmt_cause_en
  wen_mie        := getCSRWen(CSRs.mie)
  csrw_mstatus   := getCSRWen(CSRs.mstatus)
  wen_mstatus    := csrw_mstatus || cmt_status_en || cmt_mret_en
  wen_mscratch   := getCSRWen(CSRs.mscratch)

  val csr_regs   = allCSRs.map {case (addr, reg) => reg}
  val csr_rdat   = Mux1H(csr_renOh, csr_regs)

  

  io.excp_intf.mtvec := mtvec
  io.excp_intf.mepc  := mepc
  io.excp_intf.mie   := mstatus_mie
  io.excp_intf.meie  := meie
  io.excp_intf.msie  := msie
  io.excp_intf.mtie  := mtie

  io.rdValue := csr_rdat

  // to debug module
  
  dbg.dpc_r       := dpc
  dbg.ebreakm_r   := ebreakm
  dbg.step_r      := step

  dontTouch(mstatus)

}
