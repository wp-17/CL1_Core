package cl1

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix

class BPUIO extends Bundle {
  val fromIfu           = Flipped(new IF2BPUSignal())
  val x1_val            = Input(UInt(32.W))
  val jalr_rs1_x1_dep   = Input(Bool())
}


class Cl1BPU extends Module {
  val io = IO(new BPUIO)
  
  // branch decode 
  val inst  = io.fromIfu.inst
  val pc    = io.fromIfu.instPc
  val valid = io.fromIfu.ir_vld

  val jalr_rs1_x1_dep = io.jalr_rs1_x1_dep
  val x1_val  = io.x1_val

  val OPCODE_BRANCH   = "b1100011".U(7.W)  // 0x63
  val OPCODE_JAL      = "b1101111".U(7.W)  // 0x6F
  val OPCODE_JALR     = "b1100111".U(7.W) 

  val immJType  = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  val immJRType = Cat(Fill(20, inst(31)), inst(31,20))
  val immBType  = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immCJType = Cat(Fill(20, inst(12)), inst(12),inst(8), inst(10,9),inst(6), inst(7),inst(2),inst(11),inst(5,3), 0.U(1.W))
  val immCBType = Cat(Fill(23, inst(12)), inst(12),inst(6,5),inst(2),inst(11,10),inst(4,3), 0.U(1.W))

  val jalr_rs1_idx = inst(19,15)
  val cjr_rs1_idx  = inst(11,7) // for c.jr/c.jalr
  val cjr_rs2_idx  = inst(6,2)  // for c.jr/c.jalr

  val jalr_rs1_x0  = jalr_rs1_idx === 0.U
  val jalr_rs1_x1  = jalr_rs1_idx === 1.U
  val jalr_rs1_xn  = ~jalr_rs1_x0 & ~jalr_rs1_x1
  val cjr_rs1_x1   = cjr_rs1_idx === 1.U

  val cjr_rs2_eq0 = cjr_rs2_idx === 0.U

  val isBranch = (inst(6,0) === OPCODE_BRANCH)
  val isJal    = (inst(6,0) === OPCODE_JAL)
  val isJalr   = (inst(6,0) === OPCODE_JALR)
  val isCBranch = (inst(1,0) === "b01".U) & ((inst(15,13) === "b111".U) | (inst(15,13) === "b110".U)) // c.beqz, c.bnez
  val isCJ      = (inst(1,0) === "b01".U) & ((inst(15,13) === "b101".U) | (inst(15,13) === "b001".U)) // c.j, c.jal
  val isCJr     = (inst(1,0) === "b10".U) & ((inst(15,12) === "b1000".U) | (inst(15,12) === "b1001".U)) & cjr_rs2_eq0 // c.jr, c.jalr

  val branch_prdt_take  = inst(31)
  val cbranch_prdt_take = inst(12)
  val jalr_x0_take    = isJalr & jalr_rs1_x0
  val jalr_x1_take    = isJalr & jalr_rs1_x1 & ~jalr_rs1_x1_dep
  val cjr_x1_take     = isCJr & cjr_rs1_x1 & ~jalr_rs1_x1_dep // c.jr x1
  val branch_take     = isBranch & branch_prdt_take
  val cbranch_take    = isCBranch & cbranch_prdt_take
 val prdt_take  = (isJal | jalr_x0_take | jalr_x1_take | branch_take | cbranch_take | isCJ | cjr_x1_take) & valid
  // val prdt_take  = (isJal | branch_take | cbranch_take | isCJ) & valid

  val bjp_pc  = Mux1H(Seq(
    (isJal | branch_take | cbranch_take | isCJ)   -> pc,
    jalr_x0_take                              -> 0.U,
    (jalr_x1_take | cjr_x1_take)              -> x1_val
  ))

  val bjp_pc_ofst = Mux1H(Seq(
    isJal                           -> immJType,
    isCJ                            -> immCJType,
    branch_take                     -> immBType,
    (jalr_x0_take | jalr_x1_take)   -> immJRType,
    cbranch_take                    -> immCBType
  ))

  io.fromIfu.prdt_take      := prdt_take
  io.fromIfu.prdt_pc         := bjp_pc
  io.fromIfu.prdt_pc_ofst    := bjp_pc_ofst

}