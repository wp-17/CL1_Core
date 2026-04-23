package cl1

import chisel3._
import chisel3.util._
import cl1.Cl1Config.FORMAL_VERIF

class mdu_alu_req extends Bundle {
    val req  = Output(Bool())
    val op1  = Output(UInt(35.W))
    val op2  = Output(UInt(35.W))
    val sub  = Output(Bool())
    val rslt = Input(UInt(35.W))
}

class MDUIOLp extends Bundle {
    val in = Flipped(Decoupled(new Bundle{
        val rs1    = Input(UInt(32.W))
        val rs2    = Input(UInt(32.W))
        val op      = Input(UInt(4.W))
        val is_div  = Input(Bool())
        val flush   = Input(Bool())
        val b2b     = Input(Bool())
    }))
    val alu_req  = new mdu_alu_req()
    val out = Decoupled(Output(UInt(32.W)))
}

class CL1MDULp extends Module {
    val io = IO(new MDUIOLp)

    val mdu_rs1     = io.in.bits.rs1
    val mdu_rs2     = io.in.bits.rs2
    val mdu_op      = io.in.bits.op
    val mdu_is_div  = io.in.bits.is_div
    val mdu_flush   = io.in.bits.flush
    val mdu_b2b     = io.in.bits.b2b

    val alu_req     = io.alu_req

    val mdu_req_vld = io.in.valid
    val mdu_req_rdy = io.in.ready
    val mdu_rsp_vld = io.out.valid
    val mdu_rsp_rdy = io.out.ready

    val mdu_req_hsked = mdu_req_vld && mdu_req_rdy
    val mdu_rsp_hsked = mdu_rsp_vld && mdu_rsp_rdy

    val mul_op      = mdu_op(0)
    val mulh_op     = mdu_op(1)
    val mulhsu_op   = mdu_op(2)
    val mulhu_op    = mdu_op(3)

    val div_op      = mdu_op(0)
    val rem_op      = mdu_op(1)
    val divu_op     = mdu_op(2)
    val remu_op     = mdu_op(3)

    val mul_rs1_sign = Mux(mulhu_op, false.B, mdu_rs1(31))
    val mul_rs2_sign = Mux((mulhu_op | mulhsu_op), false.B, mdu_rs2(31))

    val div_rs1_sign = (div_op | rem_op) & mdu_rs1(31)
    val div_rs2_sign = (div_op | rem_op) & mdu_rs2(31)


    val mdu_idle :: mdu_exec :: mdu_check_remd :: mdu_corr_quot :: mdu_corr_remd :: nil = Enum(5)
    val mdu_st_n    = WireInit(mdu_idle)
    val mdu_st_en   = WireInit(false.B)
    val mdu_st_r    = RegEnable(mdu_st_n, mdu_idle, mdu_st_en)

    val mdu_st_idle = mdu_st_r === mdu_idle
    val mdu_st_exec = mdu_st_r === mdu_exec
    val mdu_st_check_remd = mdu_st_r === mdu_check_remd
    val mdu_st_corr_quot = mdu_st_r === mdu_corr_quot
    val mdu_st_corr_remd = mdu_st_r === mdu_corr_remd

    val special_case = Wire(Bool())
    val mdu_valid = mdu_req_vld && !mdu_flush && !mdu_b2b && !special_case

    val cnt_inc_en = Wire(Bool())
    val cnt_set_en = Wire(Bool())
    val cnt_inc_n  = Wire(UInt(6.W))
    val cnt_set_n  = 0.U(6.W)
    val exec_cnt_en = cnt_set_en | cnt_inc_en
    val exec_cnt_n = Mux1H(Seq(
        cnt_set_en -> cnt_set_n,
        cnt_inc_en -> cnt_inc_n
    ))
    val exec_cnt_r = RegEnable(exec_cnt_n, 0.U(6.W), exec_cnt_en)

    val mul_last_cycl = exec_cnt_r === 16.U
    val div_last_cycl = exec_cnt_r === 32.U
    val mul_0cycl     = exec_cnt_r === 0.U
    val div_0cycl     = exec_cnt_r === 0.U

    val mdu_st_enter_exec = mdu_st_idle && mdu_valid
    cnt_set_en := mdu_st_enter_exec
    val exec_cnt_end = Mux(mdu_is_div, div_last_cycl, mul_last_cycl)
    cnt_inc_en := mdu_st_exec && !exec_cnt_end
    cnt_inc_n  := exec_cnt_r + 1.U


    val div_rslt_not_corr = Wire(Bool())

    switch(mdu_st_r) {
        is(mdu_idle) {
            mdu_st_en := mdu_valid
            mdu_st_n  := mdu_exec
        }
        is(mdu_exec) {
            mdu_st_en := Mux(mdu_is_div, div_last_cycl, mul_last_cycl & mdu_rsp_hsked) || mdu_flush
            mdu_st_n  := Mux(mdu_is_div & !mdu_flush, mdu_check_remd, mdu_idle)
        }
        is(mdu_check_remd) {
            mdu_st_en := div_rslt_not_corr || ~div_rslt_not_corr & mdu_rsp_hsked || mdu_flush
            mdu_st_n  := Mux(div_rslt_not_corr & !mdu_flush, mdu_corr_quot, mdu_idle)
        }
        is(mdu_corr_quot) {
            mdu_st_en := true.B
            mdu_st_n := Mux(mdu_flush, mdu_idle,mdu_corr_remd)
        }
        is(mdu_corr_remd) {
            mdu_st_en := mdu_rsp_hsked || mdu_flush
            mdu_st_n  := mdu_idle
        }
    }

// booth mul 
val mul_cycl_0th    = mdu_st_exec & mul_0cycl & ~mdu_is_div
val mul_cycl_1_16th   = mdu_st_exec  & ~mul_0cycl     & ~mdu_is_div
val mul_cycl_16th   = mdu_st_exec     & mul_last_cycl & ~mdu_is_div
val mul_cycl_1_15th  = mdu_st_exec    & ~mul_last_cycl & ~mul_0cycl & ~mdu_is_div

val product_r  = Wire(UInt(66.W))

val booth_code    = Mux1H(Seq(
    mul_cycl_0th    -> Cat(mdu_rs1(1,0), false.B),
    mul_cycl_16th   -> Cat(mul_rs1_sign, mul_rs1_sign, product_r(0)),
    mul_cycl_1_15th  -> product_r(2,0)
))

//booth_code == 3'b000 =  0
//booth_code == 3'b001 =  1
//booth_code == 3'b010 =  1
//booth_code == 3'b011 =  2
//booth_code == 3'b100 = -2
//booth_code == 3'b101 = -1
//booth_code == 3'b110 = -1
//booth_code == 3'b111 = -0

val booth_sel_zero = (booth_code === 0b000.U) || (booth_code === 0b111.U)
val booth_sel_two  = (booth_code === 0b011.U) || (booth_code === 0b100.U)
val booth_sel_one  = ~booth_sel_zero & ~booth_sel_two
val booth_sel_sub  = booth_code(2)

val mul_add_rslt  = Wire(UInt(35.W))
val product_en    = mul_cycl_0th || mul_cycl_1_15th || mul_cycl_16th & mdu_rsp_hsked
val product_n     = Mux1H(Seq(
    mul_cycl_0th     -> Cat(mul_add_rslt, mdu_rs1(31,1)),
    mul_cycl_1_15th   -> Cat(mul_add_rslt, product_r(32,2)),
    mul_cycl_16th    -> Cat(mul_add_rslt(33,0), product_r(32,1))
))

val mul_add_op1 = Mux1H(Seq(
    mul_cycl_0th  -> 0.U(35.W),
    mul_cycl_1_16th -> Cat(Fill(2,product_r(65)), product_r(65,33))
))

val mul_add_op2     = Mux1H(Seq(
    booth_sel_zero -> 0.U(35.W),
    booth_sel_one  -> Cat(Fill(3,mul_rs2_sign), mdu_rs2),
    booth_sel_two  -> Cat(Fill(2,mul_rs2_sign), mdu_rs2, 0.B)
))

val mul_adder_sub  = booth_sel_sub


// div
val dividend            = Cat(Fill(33,div_rs1_sign), mdu_rs1)
val divisor             = Cat(div_rs2_sign, mdu_rs2)
val div_st_exec         = mdu_st_exec & mdu_is_div
val div_cycl_0th        = div_st_exec & div_0cycl
val div_cycl_1_32th     = div_st_exec & ~div_0cycl
val div_st_check_remd   = mdu_st_check_remd & mdu_is_div
val div_st_corr_quot    = mdu_st_corr_quot  & mdu_is_div
val div_st_corr_remd    = mdu_st_corr_remd  & mdu_is_div

val remd_quot_n         = Wire(UInt(66.W))
val remd_quot_en        = Wire(Bool())
val remd_quot_r         = Wire(UInt(66.W))

val div_add_rslt        = Wire(UInt(33.W))

val remd                = remd_quot_r(65,33)
val quot                = remd_quot_r(32,0)
val dividend_sign       = div_rs1_sign
val divisor_sign        = div_rs2_sign
val remd_sign           = remd(32)
val remd_is_zero        = remd === 0.U(33.W)
val remd_sign_not_corr  = (remd_sign ^ dividend_sign) & ~remd_is_zero
val remd_equl_divisor   = remd === divisor
val remd_neg_divisor    = div_add_rslt === 0.U
div_rslt_not_corr       := remd_sign_not_corr || remd_equl_divisor || remd_neg_divisor

val curr_quot           = ~(div_add_rslt(32) ^ divisor_sign)
val prev_quot           = remd_quot_r(0)

remd_quot_en := div_st_exec || div_st_corr_quot || div_st_corr_remd & mdu_rsp_hsked
remd_quot_n := Mux1H(Seq(
    div_cycl_0th        -> Cat(div_add_rslt(32,0), dividend(31,0), curr_quot),
    div_cycl_1_32th     -> Cat(div_add_rslt(32,0), remd_quot_r(31,0), div_last_cycl | curr_quot), // last cycle set 1
    div_st_corr_quot    -> Cat(remd_quot_r(65,33), div_add_rslt(32,0)),
    div_st_corr_remd    -> Cat(div_add_rslt(32,0), remd_quot_r(32,0))
))

val div_add_op1 = Mux1H(Seq(
    div_cycl_0th                            -> dividend(64,32),
    div_cycl_1_32th                         -> remd_quot_r(64,32),
    (div_st_check_remd | div_st_corr_remd)  -> remd,
    div_st_corr_quot                        -> quot
))

val div_add_op2 = Mux(div_st_corr_quot, 1.U, divisor)

val corr_quot_op  = (remd_sign ^ divisor_sign)
val corr_remd_op  = ~corr_quot_op
val div_adder_sub = Mux1H(Seq(
    div_cycl_0th        -> ~(dividend_sign ^ divisor_sign),
    div_cycl_1_32th     -> prev_quot,
    div_st_check_remd   -> false.B,
    div_st_corr_quot    -> corr_quot_op,
    div_st_corr_remd    -> corr_remd_op
))

val share_buffer_en = product_en || remd_quot_en
val share_buffer_n  = Mux1H(Seq(
    mdu_is_div      -> remd_quot_n,
    ~mdu_is_div     -> product_n
))
val share_buffer_r = RegEnable(share_buffer_n, 0.U(66.W), share_buffer_en)
product_r := share_buffer_r
remd_quot_r := share_buffer_r

alu_req.req := ~mdu_st_idle

alu_req.op1 := Mux1H(Seq(
    mdu_is_div  -> Cat(0.U(2.W), div_add_op1),
    ~mdu_is_div -> mul_add_op1
))

alu_req.op2 := Mux1H(Seq(
    mdu_is_div  -> Cat(0.U(2.W), div_add_op2),
    ~mdu_is_div -> mul_add_op2
))

alu_req.sub := Mux1H(Seq(
    mdu_is_div  -> div_adder_sub,
    ~mdu_is_div -> mul_adder_sub
))

mul_add_rslt := alu_req.rslt
div_add_rslt := alu_req.rslt(32,0)

val div_quot_oen = div_op | divu_op
val div_remd_oen = rem_op | remu_op
val mul_hi_oen   = mulh_op | mulhsu_op | mulhu_op
val mul_lo_oen   = mul_op

val div_by0  = (divisor === 0.U) & mdu_is_div
special_case := div_by0
val div_by0_quot = ~Fill(32,false.B)
val div_by0_remd = dividend(31,0)
val special_case_rslt = Mux1H(Seq(
    div_quot_oen -> div_by0_quot,
    div_remd_oen -> div_by0_remd
))

val mdu_b2b_oen     = ~special_case & mdu_b2b
val mul_b2b_lo      = product_r(31,0)
val div_b2b_remd    = remd_quot_r(64,33)
val mdu_b2b_rslt    = Mux1H(Seq(
    mdu_is_div      -> div_b2b_remd,
    ~mdu_is_div     -> mul_b2b_lo
))


// -- RISCV_FORMAL_ALTOPS: alternative arithmetic for formal verification --
// When FORMAL_VERIF is true, riscv-formal expects M-extension instructions
// to produce transformed results instead of real mul/div:
//   MUL/MULH/MULHU (commutative):  (rs1 + rs2) ^ bitmask
//   MULHSU/DIV/DIVU/REM/REMU:      (rs1 - rs2) ^ bitmask
// This enables formal verification of MDU control logic (state machine,
// handshake, bypassing, result selection) without complex arithmetic.
val altops_rslt = if (FORMAL_VERIF) {
  val altops_bitmask = Mux1H(Seq(
      mul_op    -> "h5876063e".U,  // MUL
      mulh_op   -> "hf6583fb7".U,  // MULH
      mulhsu_op -> "hecfbe137".U,  // MULHSU
      mulhu_op  -> "h949ce5e8".U,  // MULHU
      div_op    -> "h7f8529ec".U,  // DIV
      divu_op   -> "h10e8fd70".U,  // DIVU
      rem_op    -> "h8da68fa5".U,  // REM
      remu_op   -> "h3138d0e1".U   // REMU
  ))
  val altops_is_sub = ~(mul_op | mulh_op | mulhu_op)
  val altops_base   = Mux(altops_is_sub, mdu_rs1 - mdu_rs2, mdu_rs1 + mdu_rs2)
  altops_base ^ altops_bitmask
} else {
  0.U  // placeholder, never used
}

val mul_rslt_oen     = ~special_case & ~mdu_b2b & ~mdu_is_div
val mul_rslt_hi      = mul_add_rslt(31,0)
val mul_rslt_lo      = product_r(32,1)
val mul_rslt         = Mux1H(Seq(
    mul_hi_oen  -> mul_rslt_hi,
    mul_lo_oen  -> mul_rslt_lo
))

val div_rslt_oen    = ~special_case & ~mdu_b2b & mdu_is_div
val div_rslt_remd   = Mux(div_st_corr_remd, div_add_rslt(31,0), remd(31,0))
val div_rslt_quot   = quot(31,0)
val div_rslt        = Mux1H(Seq(
    div_quot_oen     -> div_rslt_quot,
    div_remd_oen     -> div_rslt_remd
))

io.out.bits := Mux1H(Seq(
    special_case    -> special_case_rslt,
    mdu_b2b_oen     -> mdu_b2b_rslt,
    mul_rslt_oen    -> { if (FORMAL_VERIF) altops_rslt else mul_rslt },
    div_rslt_oen    -> { if (FORMAL_VERIF) altops_rslt else div_rslt }
))


val mdu_oen =   special_case ||
                mdu_b2b      ||
                mul_cycl_16th ||
                div_st_check_remd & ~div_rslt_not_corr ||
                div_st_corr_remd
mdu_rsp_vld := mdu_oen 
mdu_req_rdy := mdu_oen & mdu_rsp_rdy 

val mdu_ck_en = mdu_st_idle & mdu_valid | ~mdu_st_idle

// for debug
dontTouch(remd)
dontTouch(quot)
dontTouch(div_quot_oen)
dontTouch(div_remd_oen)
dontTouch(div_rslt)

}