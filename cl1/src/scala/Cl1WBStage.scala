// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._

import Control._
import cl1.wb2DbgSignal
import cl1.Cl1Config._

class spike_diff extends Bundle {
  val commit = Output(Bool())
  val cInst = Output(UInt(16.W))
  val inst   = Output(UInt(32.W))
  val pc     = Output(UInt(32.W))
  val isCInst = Output(Bool())
}

class Cl1WBStage extends Module {

  val io = IO(new Bundle {
    val pplIn = Flipped(Decoupled(new IDEX2WBSignal()))

    val mem   = Flipped(Decoupled(Flipped(new LSU2WBSignal())))

    val dbg   = Flipped(new wb2DbgSignal())

    val toExcp = Flipped(new wb2Excp())

    val flush = Input(Bool())
    
    //wbStage output
    val wdata = Output(UInt(32.W))
    val csrWdat = Output(UInt(32.W))
    val forwardDat = Output(UInt(32.W))
    val wen        = Output(Bool())
    val rd_idx     = Output(UInt(5.W))
    val csrWen     = Output(Bool())
    val csr_idx    = Output(UInt(12.W))
    val valid      = Output(Bool())
    val cInst     = Output(UInt(16.W))
    val inst       = Output(UInt(32.W))
    val is_mem_load     = Output(Bool())
    val isEret    = Output(Bool())
    val wen_x1    = Output(Bool())
    val spkDiffIo = if(difftest == true) Some(new spike_diff) else None
  })

  dontTouch(io)

  val wb_valid = io.pplIn.valid

  val pplIn = io.pplIn.bits
  val instr = pplIn.inst
  val pc    = pplIn.pc
  val csr   = instr(31, 20)
  val rs1   = instr(19, 15)
  val wbType   = pplIn.wbType

  val is_c_instr = pplIn.isCInst
  val rdata    = io.mem.bits.rdata

  val wb_wfi      = pplIn.privInstr(4)
  val wb_ecall    = pplIn.privInstr(3)
  val wb_ebreak   = pplIn.privInstr(2)
  val wb_mret     = pplIn.privInstr(1)
  val wb_dret     = pplIn.privInstr(0)

  // Formal-only: under `-nordff`, every pipeline register field becomes an
  // independent anyinit variable.
  if (FORMAL_VERIF) {
    val f3_priv   = instr(14, 12) === 0.U
    val priv_form = (instr(6, 0) === "b1110011".U) &
                    (instr(11, 7)  === 0.U) &
                    (instr(19, 15) === 0.U) & f3_priv
    val f12 = instr(31, 20)
    val expected = Cat(
      priv_form & (f12 === "h105".U),  // wfi
      priv_form & (f12 === "h000".U),  // ecall
      priv_form & (f12 === "h001".U),  // ebreak
      priv_form & (f12 === "h302".U),  // mret
      priv_form & (f12 === "h7b2".U)   // dret
    )
    chisel3.assume(pplIn.privInstr === expected)
  }

  val isValidEcall  = wb_valid && wb_ecall 
  val isValidEret   = wb_valid && wb_mret
  val isValidWfi    = wb_valid && wb_wfi
  val isEbreak      = wb_valid && wb_ebreak 
  val isDret        = wb_valid && wb_dret  
  val is_valid_mem_err = false.B

  val is_mem = pplIn.memType.orR
  val is_mem_load = ~pplIn.memType(3) & pplIn.memType(2,0).orR
  io.mem.ready := wb_valid && is_mem


  val not_mem = ~is_mem
  val wb_ready_go = Mux1H(Seq(
    is_mem -> io.mem.fire,
    not_mem -> true.B
  ))
  io.pplIn.ready := !wb_valid || wb_ready_go || io.flush

  val wdata = Mux1H(Seq(
    (wbType === WB_MEM) -> rdata,
    (wbType === WB_CSR) -> pplIn.rdWdat,
    (wbType === WB_ALU) -> pplIn.rdWdat

  ))

  io.forwardDat := pplIn.rdWdat
  // io.forwardDat := wdata
  io.wdata := wdata

  val dx_nomem_ready = pplIn.dx_ready
  val dxwb_ready = Mux1H(Seq(
    is_mem -> io.mem.fire,
    not_mem -> dx_nomem_ready
  ))
  
  val ready_go  = if(WB_PIPESTAGE) wb_ready_go else dxwb_ready
  val wb_commit = wb_valid && ready_go && !io.flush

  val wen = pplIn.wen
  io.wen := wb_commit && wen

  io.csrWdat := pplIn.csrWdat
  io.csrWen   := wb_commit && pplIn.csrWen

  val wb_pc = pplIn.pc
  val diff_commit = wb_valid && ready_go && (!io.flush | wb_ecall | wb_mret)

  val rd_idx   = pplIn.inst(11,7)
  val rd_isx1  = rd_idx === 1.U
  val wb_x1wen = wen & rd_isx1
  
  io.rd_idx    := rd_idx
  io.csr_idx   := instr(31,20)
  io.valid := wb_valid
  io.cInst := pplIn.cInst
  io.inst  := pplIn.inst
  io.is_mem_load := is_mem_load
  io.isEret := isValidEret

  io.wen_x1    := wb_x1wen

  io.dbg.wb_valid   := wb_valid
  io.dbg.wb_is_ebrk := isEbreak
  io.dbg.wb_is_dret := isDret
  io.dbg.wb_pc      := pplIn.pc
  io.dbg.wb_commit  := wb_commit

  io.toExcp.cmt_ecall := isValidEcall
  io.toExcp.cmt_mret  := isValidEret
  io.toExcp.cmt_wfi   := isValidWfi
  io.toExcp.wb_valid  := wb_valid
  io.toExcp.wb_pc     := wb_pc
  io.toExcp.memNoOutStanding := Mux(is_mem, io.mem.fire, true.B)


// difftest
  if(difftest) {
    val spkDiff = io.spkDiffIo.get
    spkDiff.pc := wb_pc
    spkDiff.inst := pplIn.inst
    spkDiff.isCInst := pplIn.isCInst
    spkDiff.cInst := pplIn.cInst
    spkDiff.commit := diff_commit
  }

}