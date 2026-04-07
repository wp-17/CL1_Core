package cl1

import chisel3._
import chisel3.util._
import cl1.Cl1Config.BOOT_ADDR

class IF2IDEXSignal extends Bundle {
  val pc              = Output(UInt(32.W))
  val inst            = Output(UInt(32.W))
  val prdt_taken      = Output(UInt(32.W))
  val cInst           = Output(UInt(16.W))
  val isCInst         = Output(Bool())
  val ifu_fetch_err   = Output(Bool())
  val muldiv_b2b      = Output(Bool())
}

class IF2BPUSignal extends Bundle {
    val ir_vld        = Output(Bool())
    val inst          = Output(UInt(32.W))
    val instPc        = Output(UInt(32.W))
    val prdt_take     = Input(Bool())
    val prdt_pc       = Input(UInt(32.W))
    val prdt_pc_ofst  = Input(UInt(32.W))
}

class Cl1IFStage extends Module {
  val io = IO(new Bundle {
    val toBpu         = new IF2BPUSignal()
    val pplOut        = Decoupled(new IF2IDEXSignal())
    val toaligner     = Decoupled(new IFU2BUSignal())
    val fromaligner   = Flipped(Decoupled(new BUS2IFUSignal()))
    val fromdxu       = Flipped(new DX2IFUSignal())
    val flush         = Input(Bool())
    val flush_pc      = Input(UInt(32.W))
    val flush_pc_ofst = Input(UInt(32.W))
    val ifu_halt      = Input(Bool())
    val ifu_halt_ack  = Output(Bool())
  })

  val aligner         = BypReg(io.fromaligner)

  val excp_flush      = io.flush
  val excp_flush_pc   = io.flush_pc
  val excp_flush_pc_ofst = io.flush_pc_ofst

  val flush_pluse     = io.fromdxu.flush_req || excp_flush
  val ifu_halt        = io.ifu_halt
  val flush_pc        = Mux(excp_flush, excp_flush_pc, io.fromdxu.flush_pc)
  val flush_pc_ofst   = Mux(excp_flush, excp_flush_pc_ofst, io.fromdxu.flush_pc_ofst)

  val prdt_take   = io.toBpu.prdt_take
  // val prdt_take   = false.B
  val prdt_pc     = io.toBpu.prdt_pc
  val prdt_pc_ofst = io.toBpu.prdt_pc_ofst

  val req_hsked = io.toaligner.fire
  val rsp_hsked = aligner.fire

  val ifu_req_ready = io.toaligner.ready
  val ifu_rsp_valid = aligner.valid

  val reset_flag_n = false.B
  val reset_flag_r = RegNext(reset_flag_n, true.B)
  val reset_pluse  = reset_flag_r & ~reset_flag_n
  
  val reset_req_set   = reset_pluse 
  val reset_req_clr   = req_hsked 
  val reset_req_en    = reset_req_set | reset_req_clr
  val reset_req_n     = reset_req_set | ~reset_req_clr
  val reset_req_r     = RegEnable(reset_req_n, false.B, reset_req_en)

  val branch_req      = prdt_take

  val flush_req_r     = Wire(Bool())
  val flush_req_set   = flush_pluse & ~req_hsked
  val flush_req_clr   = flush_req_r & req_hsked
  val flush_req_en    = flush_req_set | flush_req_clr
  val flush_req_n     = flush_req_set | ~flush_req_clr
  flush_req_r         := RegEnable(flush_req_n, false.B, flush_req_en)
  val flush_req_real  = flush_pluse | flush_req_r

  val ifu_out_r       = Wire(Bool())
  val ifu_out_set     = req_hsked
  val ifu_out_clr     = ifu_out_r & rsp_hsked
  val ifu_out_en      = ifu_out_set | ifu_out_clr
  val ifu_out_n       = ifu_out_set | ~ifu_out_clr
  ifu_out_r           := RegEnable(ifu_out_n, false.B, ifu_out_en)
  val ifu_req_condi   = ~ifu_out_r | ifu_out_clr

  val ifu_new_req     = ~ifu_halt & ~reset_flag_r
  val ifu_req         = ifu_new_req | reset_req_r | branch_req | flush_req_real

  val ifu_req_valid   = ifu_req & ifu_req_condi
  val is_c            = Wire(Bool())
  val pc_incr_size    = Mux(is_c, 2.U, 4.U)

  val pc_r            = Wire(UInt(32.W))
  val pc_adder_op1    = Mux(flush_pluse,    flush_pc,
                        Mux(flush_req_r,    pc_r,
                        Mux(branch_req,     prdt_pc,
                        Mux(reset_req_r,    BOOT_ADDR.U,
                        pc_r))))

  val pc_adder_op2    = Mux(flush_pluse,   flush_pc_ofst,
                        Mux(flush_req_r    | reset_req_r, 0.U,
                        Mux(branch_req,     prdt_pc_ofst,
                        pc_incr_size)))

  val pc_adder_rslt = pc_adder_op1 + pc_adder_op2

  val pc_n          = Cat(pc_adder_rslt(31,1),false.B)
  val pc_en         = req_hsked | flush_pluse
  pc_r              := RegEnable(pc_n, 0.U(32.W), pc_en)

  val ir_vld_r      = Wire(Bool())
  val ir_o_rdy      = io.pplOut.ready
  val ir_o_hsked    = ir_vld_r & ir_o_rdy
  val ir_vld_set    = rsp_hsked & ~flush_req_real
  val ir_vld_clr    = ir_o_hsked
  val ir_vld_en     = ir_vld_set | ir_vld_clr
  val ir_vld_n      = ir_vld_set | ~ir_vld_clr
  ir_vld_r          := RegEnable(ir_vld_n, false.B, ir_vld_en)

  val ifu_pc_n      = pc_r
  val ifu_pc_en     = ir_vld_set
  val ifu_pc        = RegEnable(ifu_pc_n, 0.U, ifu_pc_en)
  
  val prdt_taken_en     = ir_vld_set
  val prdt_taken        = RegEnable(prdt_take, 0.U, prdt_taken_en)

  val isC_en        = ir_vld_set
  val isC_r         = RegEnable(is_c, 0.U, isC_en)

  val fetch_inst    = aligner.bits.inst
  is_c              := fetch_inst(1,0) =/= "b11".U
  val c_inst        = fetch_inst(15,0)
  val cinst_r       = RegEnable(c_inst, 0.U, is_c & ir_vld_set)

  val rvcexpander   = Module(new Cl1RVCExpander())
  rvcexpander.io.inst  := Mux(is_c, c_inst, 0.U)
  val expand_inst      = rvcexpander.io.out

  val ir_n          = Mux(is_c, expand_inst, fetch_inst)
  val ir_en         = ir_vld_set
  val ir            = RegEnable(ir_n, 0.U(32.W), ir_en)

  // check b2b
  val dxudec_muldiv     = io.fromdxu.decmuldiv_info
  val ir_opcode         = ir_n(6,0)
  val muldiv_op         = (ir_opcode === "b0110011".U)
  val ifudec_mul        = muldiv_op & (ir_n(14,12) === "b000".U)
  val ifudec_mulhsu     = muldiv_op & (ir_n(14) === false.B) && ir_n(13,12).orR
  val ifudec_div        = muldiv_op & (ir_n(14,12) === "b100".U)
  val ifudec_divu       = muldiv_op & (ir_n(14,12) === "b101".U)
  val ifudec_rem        = muldiv_op & (ir_n(14,12) === "b110".U)
  val ifudec_remu       = muldiv_op & (ir_n(14,12) === "b111".U)

  val dxudec_mul_all    = ~dxudec_muldiv(4)
  val dxudec_div_all    = dxudec_muldiv(4)
  val dxudec_mul        = dxudec_mul_all & dxudec_muldiv(0)
  val dxudec_mulhsu     = dxudec_mul_all & dxudec_muldiv(3,1).orR
  val dxudec_div        = dxudec_div_all & dxudec_muldiv(0)
  val dxudec_rem        = dxudec_div_all & dxudec_muldiv(1)
  val dxudec_divu       = dxudec_div_all & dxudec_muldiv(2)
  val dxudec_remu       = dxudec_div_all & dxudec_muldiv(3)

  val ir_rs1idx         = ir_n(19,15)
  val ir_rs2idx         = ir_n(24,20)
  val ir_rdidx          = ir_n(11,7)
  val ir_rs1idx_r       = ir(19,15)
  val ir_rs2idx_r       = ir(24,20)
  val ir_rdidx_r        = ir(11,7)

  val muldiv_b2b_n      = ((dxudec_mulhsu & ifudec_mul) |
                          (dxudec_div    & ifudec_rem) |
                          (dxudec_divu   & ifudec_remu) |
                          (dxudec_rem    & ifudec_div) |
                          (dxudec_remu   & ifudec_divu)) &
                          (ir_rs1idx_r === ir_rs1idx) & 
                          (ir_rs2idx_r === ir_rs2idx) &
                          (ir_rs1idx_r  =/= ir_rdidx_r) &
                          (ir_rs2idx_r  =/= ir_rdidx_r)
  
  val muldiv_b2b_r      = RegEnable(muldiv_b2b_n, false.B, ir_vld_set)

  val fetch_err_n   = aligner.bits.err =/= 0.U
  val fetch_err_en  = ir_vld_set
  val fetch_err_r   = RegEnable(fetch_err_n, false.B, fetch_err_en)

  io.pplOut.bits.pc           := ifu_pc
  io.pplOut.bits.inst         := ir
  io.pplOut.bits.prdt_taken   := prdt_taken
  io.pplOut.bits.cInst        := cinst_r
  io.pplOut.bits.isCInst      := isC_r
  io.pplOut.bits.ifu_fetch_err := fetch_err_r
  io.pplOut.bits.muldiv_b2b   := muldiv_b2b_r

  io.pplOut.valid        := ir_vld_r

  val ifu_rsp_ready      = Mux(flush_req_real, 1.U, (~ir_vld_r | ir_vld_clr) & ifu_req_ready)

  io.toBpu.ir_vld        := ifu_rsp_valid
  io.toBpu.instPc        := pc_r 
  // io.toBpu.inst          := ir_n
  io.toBpu.inst          := fetch_inst

  aligner.ready   := ifu_rsp_ready
  io.toaligner.valid     := ifu_req_valid
  io.toaligner.bits.req_pc    := pc_n
  io.toaligner.bits.req_seq   := ~(reset_req_r | branch_req | flush_req_real)
  io.toaligner.bits.pc_reg    := pc_r

  // wfi halt
  val ifu_no_out   = ~ifu_out_r | ifu_rsp_valid
  val ifu_halt_ack = Wire(Bool())
  val ifu_halt_ack_set = ifu_halt & ~ifu_halt_ack & ifu_no_out
  val ifu_halt_ack_clr = ifu_halt_ack & ~ifu_halt
  val ifu_halt_ack_en  = ifu_halt_ack_set | ifu_halt_ack_clr
  val ifu_halt_ack_n   = ifu_halt_ack_set | ~ifu_halt_ack_clr
  ifu_halt_ack         := RegEnable(ifu_halt_ack_n, false.B, ifu_halt_ack_en)
  io.ifu_halt_ack      := ifu_halt_ack
}

