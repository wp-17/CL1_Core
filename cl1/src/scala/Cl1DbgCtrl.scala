package cl1

import chisel3._
import chisel3.util._
import cl1.Cl1Config.DBG_ENTRYADDR

object DbgCause {
    val None            = 0.U(3.W)
    val ebreak          = 1.U(3.W)
    val trigger         = 2.U(3.W)
    val haltreq         = 3.U(3.W)
    val step            = 4.U(3.W)
    val resethaltreq    = 5.U(3.W)
}

class Dbg2CsrSignal extends Bundle {
    val csr_update_en   = Output(Bool())
    val dpc_update      = Output(UInt(32.W))
    val dbg_cause       = Output(UInt(3.W))

    val dpc_r           = Input(UInt(32.W))
    val ebreakm_r       = Input(Bool())
    val step_r          = Input(Bool())
}

class wb2DbgSignal extends Bundle {
    val wb_valid       = Input(Bool())
    val wb_commit      = Input(Bool())
    val wb_is_ebrk     = Input(Bool())
    val wb_is_dret     = Input(Bool())
    val wb_pc          = Input(UInt(32.W))
    // val ebrk_excp_en   = Output(Bool())
    // val dbg_irq_mask   = Output(Bool())
}

class Cl1DM extends Module {
    val io = IO(new Bundle {
        val dbg2csr                     = new Dbg2CsrSignal()
        val wb2dbg                      = new wb2DbgSignal()
        val dbg2excp                    = Flipped(new dbg2excp())
        val dbg_external_req_i          = Input(Bool())
        val dbg_flush                   = Output(Bool())
        val dbg_flush_pc                = Output(UInt(32.W))
    })

    // debug req priority from top to down 
    // dbg_trig_req (priority 4)
    // dbg_ebrk_req (priority 3)
    // dbg_resethalt_req (priority 2)
    // dbg_halt_req (priority 1)
    // dbg_step_req (priority 0)

    val ebreakm_r           = io.dbg2csr.ebreakm_r
    val dbg_external_req    = io.dbg_external_req_i
    val csr_step_r          = io.dbg2csr.step_r
    val dpc_r               = io.dbg2csr.dpc_r

    val wb_valid           = io.wb2dbg.wb_valid
    val wb_commit          = io.wb2dbg.wb_commit
    val wb_is_ebrk         = io.wb2dbg.wb_is_ebrk
    val wb_is_dret         = io.wb2dbg.wb_is_dret
    val wb_pc              = io.wb2dbg.wb_pc
    
    // debug mode
    val dbg_take_en  = WireInit(false.B)
    val dbg_exit_en  = WireInit(false.B)
    val dbg_mode_en  = dbg_take_en | dbg_exit_en
    val dbg_mode_n   = dbg_take_en | ~dbg_exit_en
    val dbg_mode_r   = RegEnable(dbg_mode_n, false.B, dbg_mode_en)

    // step
    val step_req_set = (~dbg_mode_r) & csr_step_r & wb_commit & (~dbg_take_en)
    val step_req_clr = dbg_take_en
    val step_req_en  = step_req_set | step_req_clr
    val step_req_n   = step_req_set | ~step_req_clr
    val step_req_r   = RegEnable(step_req_n, false.B, step_req_en)

    // ebreak entry dbg
    val ebrk4dbg        = wb_is_ebrk & ebreakm_r 
    val ebrk_renter_dbg = wb_is_ebrk & dbg_mode_r & wb_valid
    val ebrk4excp       = wb_is_ebrk & ~ebreakm_r & ~dbg_mode_r
    
    // debug request
    val dbg_ebrk_req   = ebrk4dbg & ~step_req_r
    val dbg_halt_req   = dbg_external_req & ~dbg_ebrk_req & ~step_req_r
    val dbg_step_req   = step_req_r
    
    val dbg_take_req   = dbg_ebrk_req | dbg_halt_req | dbg_step_req
    val dbg_take_condi = wb_valid & ~dbg_mode_r
    dbg_take_en        := dbg_take_req & dbg_take_condi

    val dbg_take_flush = dbg_take_en | ebrk_renter_dbg
    val dbg_flush_pc   = DBG_ENTRYADDR.U

    // dret 
    dbg_exit_en        := wb_is_dret & wb_valid
    val dret_flush     = dbg_exit_en
    val dret_flush_pc  = dpc_r

    // update csr 
    val csr_update_en   = dbg_take_en
    val dpc_update      = wb_pc
    val dbg_cause       =   Mux(dbg_ebrk_req, DbgCause.ebreak, 
                            Mux(dbg_halt_req, DbgCause.haltreq,
                            Mux(dbg_step_req, DbgCause.step, 
                            DbgCause.None)))

    // flush 
    val flush           = dbg_take_flush | dret_flush
    val flush_pc        = Mux(dbg_take_flush, dbg_flush_pc,
                          Mux(dret_flush, dret_flush_pc, 0.U))

    io.dbg2csr.csr_update_en := csr_update_en
    io.dbg2csr.dpc_update    := dpc_update
    io.dbg2csr.dbg_cause     := dbg_cause

    io.dbg_flush            := flush
    io.dbg_flush_pc         := flush_pc

    io.dbg2excp.ebrk_excp_en    := ebrk4excp & wb_valid
    io.dbg2excp.debug_irq_mask  := dbg_mode_r | csr_step_r
    io.dbg2excp.debug_mode      := dbg_mode_r
    io.dbg2excp.debug_take_req  := dbg_take_req
   
}