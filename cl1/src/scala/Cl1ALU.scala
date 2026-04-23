package cl1

import chisel3._
import chisel3.util._
import Control._

class alu_io extends Bundle {
  val misc_req = new Bundle {
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val op  = Input(UInt(ALU_WIDTH.W))
    val res = Output(UInt(32.W))
    val eq  = Output(Bool())
    val lt  = Output(Bool())
  }
  val mdu_req = Flipped(new mdu_alu_req())
}

class Cl1ALU extends Module {
  val io = IO(new alu_io())

  val aluops_oh   = io.misc_req.op
  val a           = io.misc_req.a
  val b           = io.misc_req.b

  val mdu_adder_op1  = io.mdu_req.op1
  val mdu_adder_op2  = io.mdu_req.op2
  val mdu_adder_sub  = io.mdu_req.sub
  val mdu_adder_req  = io.mdu_req.req
  
  val aluop_add   = aluops_oh(9)
  val aluop_sub   = aluops_oh(8)
  val aluop_and   = aluops_oh(7)
  val aluop_or    = aluops_oh(6)
  val aluop_xor   = aluops_oh(5)
  val aluop_sll   = aluops_oh(4)
  val aluop_slt   = aluops_oh(3)
  val aluop_sltu  = aluops_oh(2)
  val aluop_srl   = aluops_oh(1)
  val aluop_sra   = aluops_oh(0)

  val misc_adder_opadd = aluop_add
  val misc_adder_opsub = aluop_sub | aluop_sltu | aluop_slt
  val adder_unsig = aluop_sltu 

  val adder_op1 = Wire(UInt(35.W))
  val adder_op2 = Wire(UInt(35.W))
  val adder_res = Wire(UInt(35.W))

  val misc_adder_op1 = Cat(0.U(2.W), ~adder_unsig & a(31), a)
  val misc_adder_op2 = Cat(0.U(2.W), ~adder_unsig & b(31), b)

  adder_op1 := Mux(mdu_adder_req, mdu_adder_op1, misc_adder_op1)
  adder_op2 := Mux(mdu_adder_req, mdu_adder_op2, misc_adder_op2)
  val adder_sub = Mux(mdu_adder_req, mdu_adder_sub, misc_adder_opsub) 
  val adder_cin = adder_sub
  adder_res := adder_op1 + (Fill(35,adder_sub).asUInt ^ adder_op2) + adder_cin

  val misc_adder_res = adder_res(32,0)
  val slt_res  = misc_adder_res(32)

  val and_res  = a & b 
  val or_res   = a | b 
  val xor_res  = a ^ b

  val srl_op1  = Mux(aluop_sll, Reverse(a), a)
  val srl_op2  = b(4,0)

  //TODO: Try to use Chisel utility to do this
  val srl_res  = srl_op1 >> srl_op2
  val sll_res  = Reverse(srl_res)
  val sra_mask = ~(Fill(32,true.B).asUInt >> srl_op2)
  val sra_res  = srl_res | sra_mask & Fill(32,a(31)).asUInt

  io.misc_req.res :=  Fill(32,aluop_add | aluop_sub).asUInt & misc_adder_res |
                      Fill(32, aluop_slt | aluop_sltu).asUInt & (Fill(31, false.B) ## slt_res) |
                      Fill(32,aluop_and) & and_res                     |
                      Fill(32,aluop_or)  & or_res                      |
                      Fill(32,aluop_xor) & xor_res                     |
                      Fill(32,aluop_sll) & sll_res                     |
                      Fill(32,aluop_srl) & srl_res                     |
                      Fill(32,aluop_sra) & sra_res

  io.misc_req.eq := ~xor_res.orR
  io.misc_req.lt := slt_res

  io.mdu_req.rslt := adder_res
}