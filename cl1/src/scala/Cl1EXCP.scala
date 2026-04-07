package cl1
import chisel3._
import chisel3.util._
import Cl1Config._

trait TrapCode {

  val IRQ_BIT = 31
  val M_SFTER_IRQ           = 1.U(1.W) ## 3.U(31.W)
  val M_TIMER_IRQ           = 1.U(1.W) ## 7.U(31.W)
  val M_EXTER_IRQ           = 1.U(1.W) ## 11.U(31.W)
  val INST_MISALIGNED_EXPT  = 0.U(1.W) ## 0.U(31.W)
  val INST_ACCESS_EXPT      = 0.U(1.W) ## 1.U(31.W)
  val INST_ILLEGAL_EXPT     = 0.U(1.W) ## 2.U(31.W)
  val BREAKPOINT_EXPT       = 0.U(1.W) ## 3.U(31.W)
  val LOAD_MISALIGNED_EXPT  = 0.U(1.W) ## 4.U(31.W)
  val LOAD_ACCESS_EXPT      = 0.U(1.W) ## 5.U(31.W)
  val STORE_MISALIGNED_EXPT = 0.U(1.W) ## 6.U(31.W)
  val STORE_ACCESS_EXPT     = 0.U(1.W) ## 7.U(31.W)
  val U_ECALL_EXPT          = 0.U(1.W) ## 8.U(31.W)
  val S_ECALL_EXPT          = 0.U(1.W) ## 9.U(31.W)
  val M_ECALL_EXPT          = 0.U(1.W) ## 11.U(31.W)
}

class wb2Excp extends Bundle {
    val cmt_ecall     = Input(Bool())
    val cmt_mret      = Input(Bool())
    val cmt_wfi       = Input(Bool())
    val wb_valid      = Input(Bool())
    val wb_pc         = Input(UInt(32.W))
    val memNoOutStanding = Input(Bool())
}

class excp2Csr extends Bundle {
    val ext_irq     = Output(Bool())
    val sft_irq     = Output(Bool())
    val tmr_irq     = Output(Bool())
    val meie        = Input(Bool())
    val msie        = Input(Bool())
    val mtie        = Input(Bool())
    val mie         = Input(Bool())
    val mepc        = Input(UInt(32.W))
    val mtvec       = Input(UInt(32.W))
    val cmt_epc_en      = Output(Bool())
    val cmt_epc_n       = Output(UInt(32.W))
    val cmt_status_en   = Output(Bool())
    val cmt_cause_en    = Output(Bool())
    val cmt_cause_n     = Output(UInt(32.W))
    val cmt_mret_en     = Output(Bool())
}

class dbg2excp extends Bundle {
    val debug_mode      = Input(Bool())
    val debug_irq_mask  = Input(Bool())
    val ebrk_excp_en    = Input(Bool())
    val debug_take_req  = Input(Bool())
}


class Cl1EXCPIO() extends Bundle {
    val ext_irq             = Input(Bool())
    val sft_irq             = Input(Bool())
    val tmr_irq             = Input(Bool())
    val flush               = Output(Bool())
    val flush_pc            = Output(UInt(32.W))
    val flush_ofst          = Output(UInt(32.W))
    val ifu_halt            = Output(Bool())
    val ifu_halt_ack        = Input(Bool())
    val dxu_halt            = Output(Bool())
    val dxu_halt_ack        = Input(Bool())
    val icache_idle         = Input(Bool())
    val dcache_idle         = Input(Bool())
    val core_wfi            = Output(Bool())
    val excp2Csr            = new excp2Csr()
    val dbg2excp            = new dbg2excp()
    val wb2Excp             = new wb2Excp()
}

class Cl1EXCP() extends Module with TrapCode {
    val io = IO(new Cl1EXCPIO())

    val ext_irq = io.ext_irq
    val sft_irq = io.sft_irq
    val tmr_irq = io.tmr_irq

    val meie    = io.excp2Csr.meie
    val msie    = io.excp2Csr.msie
    val mtie    = io.excp2Csr.mtie
    val mie     = io.excp2Csr.mie
    val mepc    = io.excp2Csr.mepc
    val mtvec   = io.excp2Csr.mtvec

    val cmt_ecall       = io.wb2Excp.cmt_ecall
    val cmt_mret        = io.wb2Excp.cmt_mret
    val cmt_wfi         = io.wb2Excp.cmt_wfi
    val ebrk_excp_en    = io.dbg2excp.ebrk_excp_en
    val wb_valid        = io.wb2Excp.wb_valid
    val wb_pc           = io.wb2Excp.wb_pc
    val no_outstanding_mem_access = io.wb2Excp.memNoOutStanding

    val debug_irq_mask  = io.dbg2excp.debug_irq_mask
    val debug_mode      = io.dbg2excp.debug_mode
    val debug_take_req  = io.dbg2excp.debug_take_req

    val irq_req_raw =   ext_irq & meie |
                        sft_irq & msie |
                        tmr_irq & mtie
    val irq_mask    =   ~mie | debug_irq_mask
    val irq_req     =  irq_req_raw & ~irq_mask
    val irq_casue   =  MuxCase(0.U, Seq(
                        (sft_irq & msie) -> M_SFTER_IRQ,
                        (tmr_irq & mtie) -> M_TIMER_IRQ,
                        (ext_irq & meie) -> M_EXTER_IRQ
    ))

    val excp_take_en    = cmt_ecall | ebrk_excp_en
    val excp_cause       = MuxCase(0.U, Seq(
                            ebrk_excp_en -> BREAKPOINT_EXPT,
                            cmt_ecall    -> M_ECALL_EXPT
                        ))
    val trap_exit_en     = cmt_mret
    val irq_take_en      = irq_req & no_outstanding_mem_access & wb_valid
    val trap_take_en     = irq_take_en | excp_take_en
    val cmt_epc_en       = trap_take_en 
    val cmt_epc_n        = wb_pc
    val cmt_status_en    = trap_take_en
    val cmt_cause_en     = trap_take_en
    val cmt_cause_n      = Mux(excp_take_en, excp_cause, irq_casue)
    val cmt_mret_en      = cmt_mret

    val direct_mode       = (mtvec(1,0) === 0.U)
    val vector_mode       = (mtvec(1,0) === 1.U)
    val mtvec_base        = Cat(mtvec(31,2),0.U(2.W))

    val debug_excp_base         = Cl1Config.DBG_EXCP_BASE.U
    val trap_take_flush         = trap_take_en
    val trap_take_flush_pc      = Mux(debug_mode, debug_excp_base, mtvec_base)
    val is_interrupt            = cmt_cause_n(31)
    val trap_take_flush_ofst    = Mux(vector_mode & is_interrupt & trap_take_en, cmt_cause_n(3,0) << 2, 0.U)

    val trap_exit_flush         = trap_exit_en
    val trap_exit_flush_pc      = mepc

    val flush              = trap_take_flush | trap_exit_flush
    val flush_pc           = Mux(trap_take_flush, trap_take_flush_pc, trap_exit_flush_pc)
    val flush_ofst         = trap_take_flush_ofst

    // wfi
    val wfi_cmt_vld        = cmt_wfi & !debug_mode
    val wfi_halt_req_set   = wfi_cmt_vld
    val wfi_halt_req_clr   = irq_req_raw | debug_take_req
    val wfi_halt_req_en    = wfi_halt_req_set | wfi_halt_req_clr
    val wfi_halt_req_n     = wfi_halt_req_set & ~wfi_halt_req_clr
    val wfi_halt_req       = RegEnable(wfi_halt_req_n, false.B, wfi_halt_req_en)

    val wfi_ifu_halt       = wfi_halt_req | wfi_halt_req_n
    val wfi_dxu_halt       = wfi_ifu_halt
    val wfi_ifu_halt_ack   = io.ifu_halt_ack
    val wfi_dxu_halt_ack   = io.dxu_halt_ack
    val cpu_halt_done      = wfi_ifu_halt & wfi_ifu_halt_ack & wfi_dxu_halt & wfi_dxu_halt_ack & io.icache_idle & io.dcache_idle

    val core_wfi           = Wire(Bool())
    val core_wfi_set       = cpu_halt_done & ~core_wfi
    val core_wfi_clr       = wfi_halt_req_clr
    val core_wfi_en        = core_wfi_set | core_wfi_clr
    val core_wfi_n         = core_wfi_set & ~core_wfi_clr
    core_wfi               := RegEnable(core_wfi_n, false.B, core_wfi_en)
    val core_wfi_o         = core_wfi & ~core_wfi_clr

    io.excp2Csr.ext_irq    := ext_irq
    io.excp2Csr.sft_irq    := sft_irq
    io.excp2Csr.tmr_irq    := tmr_irq
    io.excp2Csr.cmt_epc_en := cmt_epc_en
    io.excp2Csr.cmt_epc_n  := cmt_epc_n
    io.excp2Csr.cmt_status_en := cmt_status_en
    io.excp2Csr.cmt_cause_en  := cmt_cause_en
    io.excp2Csr.cmt_cause_n   := cmt_cause_n
    io.excp2Csr.cmt_mret_en   := cmt_mret_en

    io.flush               := flush
    io.flush_pc            := flush_pc
    io.flush_ofst          := flush_ofst

    val dx_halt            = if(WB_PIPESTAGE) wfi_dxu_halt else false.B

    io.ifu_halt            := wfi_ifu_halt
    io.dxu_halt            := dx_halt
    io.core_wfi            := core_wfi_o
}
