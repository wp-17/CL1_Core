// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import cl1.Cl1Config._
import Cl1PowerSaveConfig._
import Cl1PowerSaveConfig._
import chisel3.util.circt.ClockGate
import chisel3.util.experimental.BoringUtils

import Control._

class IDEX2BPUSignal extends Bundle {
  val jalrAddr = Output(UInt(32.W))
  val jumpType = Output(UInt(2.W))
  val take  = Output(Bool())
  val offset    = Output(UInt(32.W))
  val pc        = Output(UInt(32.W))
}

class IDEX2LSUSignal extends Bundle {
  val memType = Output(UInt(MEM_WIDTH.W))
  val addr    = Output(UInt(32.W))
  val wdata   = Output(UInt(32.W))
}

class IDEX2WBSignal extends Bundle {
  val wbType       = Output(UInt(WB_WIDTH.W))
  val rdWdat       = Output(UInt(32.W))
  val csrWdat      = Output(UInt(32.W))
  val wen          = Output(Bool())
  val csrWen       = Output(Bool())
  val memType      = Output(UInt(MEM_WIDTH.W))
  val pc           = Output(UInt(32.W))
  val privInstr    = Output(UInt(5.W))
  val inst         = Output(UInt(32.W))
  val cInst        = Output(UInt(16.W))
  val isCInst      = Output(Bool())
  val isTrap       = Output(Bool())
  val trapCode     = Output(UInt(8.W))
  val dx_ready     = Output(Bool())
}

class DX2IFUSignal extends Bundle {
  val flush_req       = Output(Bool())
  val flush_pc        = Output(UInt(32.W))
  val flush_pc_ofst   = Output(UInt(32.W))
  val decmuldiv_info  = Output(UInt(5.W))
}


class Cl1IDEXStage extends Module {
  val io = IO(new Bundle {
    val pplIn  = Flipped(Decoupled(new IF2IDEXSignal()))
    val pplOut = Decoupled(new IDEX2WBSignal())

    val mem    = Decoupled(new IDEX2LSUSignal())
    val toifu  = new DX2IFUSignal()

    val rs1Value = Input(UInt(32.W))
    val rs2Value = Input(UInt(32.W))
    val rs1Addr  = Output(UInt(5.W))
    val rs2Addr  = Output(UInt(5.W))
    val rs1_ren  = Output(Bool())
    val rs2_ren  = Output(Bool())

    val csrData  = Input(UInt(32.W))
    val csrAddr  = Output(UInt(12.W))
    val csrRen   = Output(Bool())

    val stall    = Input(Bool())
    val dxu_halt_ack = Output(Bool())
    val valid    = Output(Bool())
    val flush    = Input(Bool())

    val wen_x1   = Output(Bool())

    val icache_req = Decoupled(new dxReq)
    val dcache_req = Decoupled(new dxReq)
  })

  dontTouch(io)

  def priv_dec(instr: UInt) = {
    val opcode_system   = instr(6,0)    === "b1110011".U
    val rs1_zero        = instr(11,7)   === "b00000".U
    val rd_zero         = instr(19,15)  === "b00000".U
    val funct3_priv     = inst(14,12)   === "b000".U
    val funct12_ecall   = inst(31,20)   === ("b" + "0" * 11).U
    val funct12_ebreak  = inst(31,20)   === ("b" + "0" * 10 + "1").U 
    val funct12_mret    = inst(31,20)   === ("b0011000" + "00010").U
    val funct12_dret    = inst(31,20)   ===  "h7b2".U
    val funct12_wfi     = inst(31,20)   === ("b0001000" + "00101").U

    def instr_priv_comp(func: UInt) = {
      opcode_system & rd_zero & funct3_priv & rs1_zero & func
    }
    
    val instr_ecall     = opcode_system & rd_zero & funct3_priv & rs1_zero & funct12_ecall
    val instr_ebreak    = opcode_system & rd_zero & funct3_priv & rs1_zero & funct12_ebreak
    val instr_mret      = opcode_system & rd_zero & funct3_priv & rs1_zero & funct12_mret
    val instr_dret      = opcode_system & rd_zero & funct3_priv & rs1_zero & funct12_dret
    val instr_wfi       = instr_priv_comp(funct12_wfi)

    Cat(instr_wfi,instr_ecall, instr_ebreak, instr_mret, instr_dret)
  }

  val decoder = Module(new Cl2Decoder())
  val mdu     = Module(new CL1MDULp())
  val alu     = Module(new Cl1ALU)

  /* This 'valid' register means the input of this stage is valid, which
   is NOT the handshake signal. */
  val dx_valid  = io.pplIn.valid
  val dx_flush  = io.flush
  val dx_stall  = io.stall
  val wb_ready  = io.pplOut.ready

  val inst = io.pplIn.bits.inst
  val pc    = io.pplIn.bits.pc
  val mdu_b2b = io.pplIn.bits.muldiv_b2b

  decoder.io.inst := inst

  val ctrl = decoder.io.out
  val rs1   = inst(19, 15)
  val rs2   = inst(24, 20)
  val rd    = inst(11, 7)
  val csr_idx   = inst(31, 20)

  val jType = ctrl.jType
  val csrType = ctrl.csrType
  val is_jalr = jType(1)
  val is_jal = jType(0)
  val isPRIV  = csrType.andR //TODO:
  val isCSRRW = csrType(CSRRW_BIT) && !isPRIV
  val isCSRRC = csrType(CSRRC_BIT) && !isPRIV
  val isCSRRS = csrType(CSRRS_BIT) && !isPRIV
  val isCSRI  = csrType(CSRI_BIT)  && !isPRIV
  val aSel    = ctrl.aSel
  val bSel    = ctrl.bSel

  val instr_size    = Wire(UInt(3.W))

  val Uimm = Cat(io.pplIn.bits.inst(31, 12), 0.U(12.W))
  val Iimm = SignExt(inst(31, 20), 32)
  val Bimm = SignExt(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)), 32)
  val Simm = SignExt(Cat(inst(31, 25), inst(11, 7)), 32)
  val Jimm = SignExt(Cat(inst(31), inst(19, 12), inst(20), inst(30, 25), inst(24, 21), 0.U(1.W)), 32)
  val csrImm = ZeroExt(rs1, 32)

  val imm = Wire(UInt(32.W))
  val immType = ctrl.immType
  imm := Mux1H(Seq(
    immType(UIMM_BIT) -> Uimm,
    immType(IIMM_BIT) -> Iimm,
    immType(BIMM_BIT) -> Bimm,
    immType(SIMM_BIT) -> Simm,
    immType(JIMM_BIT) -> Jimm
  ))

  val a = Mux1H(Seq(
    aSel(ASEL_REG_BIT) -> Mux(isCSRRC, ~io.rs1Value, io.rs1Value), //for CSRRS, a = ~rs1
    aSel(ASEL_PC_BIT) -> pc,
    aSel(ASEL_CSRIMM_BIT) -> Mux(isCSRRC, ~csrImm,  csrImm), //for CSRRS, a = ~rs1
    aSel(ASEL_Z_BIT) -> 0.U
  ))

  val b = Mux1H(Seq(
    bSel(BSEL_IMM_BIT) -> imm,
    bSel(BSEL_REG_BIT) -> io.rs2Value,
    bSel(BSEL_CSR_BIT) -> io.csrData,
    bSel(BSEL_FOUR_BIT) -> instr_size
  ))

  alu.io.misc_req.a := a
  alu.io.misc_req.b := b
  alu.io.misc_req.op := ctrl.aluOp
  if(Cl1Config.MDU_SHAERALU == true) {
    alu.io.mdu_req <> mdu.io.alu_req
  } else {
    alu.io.mdu_req.op1 := 0.U
    alu.io.mdu_req.op2 := 0.U
    alu.io.mdu_req.sub := false.B
    alu.io.mdu_req.req := false.B
    val mdu_adder      = mdu.io.alu_req
    val mdu_adder_rslt = mdu_adder.op1 + (Fill(mdu_adder.op2.getWidth,mdu_adder.sub) ^ mdu_adder.op2) + mdu_adder.sub
    mdu_adder.rslt := mdu_adder_rslt
    }

  val is_store = ctrl.memType(MEM_LS_BIT)
  val is_mem = ctrl.memType.orR

  val csrRden  = dx_valid & (isCSRRW & rd.orR | ( isCSRRC | isCSRRS ))  & ~dx_stall & ~dx_flush
  val csrWren  = dx_valid & (isCSRRW | ( isCSRRC | isCSRRS ) & rs1.orR)  & ~dx_stall & ~dx_flush

  val rs1_ren  = rs1.orR
  val rs2_ren  = rs2.orR

  val rd_notzero = rd.orR
  val rd_wen = rd_notzero & ctrl.wbWen

  io.csrRen := csrRden
  io.csrAddr := csr_idx
  io.rs1Addr := rs1
  io.rs2Addr := rs2
  io.rs1_ren := rs1_ren
  io.rs2_ren := rs2_ren

  val op_is_mdu = ~(ctrl.muldivOp === MD_NONE)
  val mdu_out = mdu.io.out
  mdu_out.ready := !dx_stall & io.pplOut.ready

  val brchmis_slv = Wire(Bool())
  val brchmis_flush_pluse = Wire(Bool())
  
  val fu_hsked = Mux1H(Seq(
    is_mem -> io.mem.fire,
    brchmis_slv  -> brchmis_flush_pluse, // flush for one cycle
    op_is_mdu -> mdu_out.fire
  ))
  val exec_done_set  = fu_hsked & dx_valid
  val exec_done_clr  = io.pplIn.fire
  val exec_done_en   = exec_done_set | exec_done_clr
  val exec_done_n    = exec_done_set & ~exec_done_clr
  val dx_exec_done   = RegEnable(exec_done_n, false.B, exec_done_en)

  dontTouch(exec_done_set)
  dontTouch(exec_done_clr)

  
  // mdu
  val mdu_in = mdu.io.in
  mdu_in.valid := dx_valid && !dx_exec_done && !dx_flush && op_is_mdu && !dx_stall
  mdu_in.bits.rs1 := io.rs1Value
  mdu_in.bits.rs2 := io.rs2Value
  mdu_in.bits.op  := ctrl.muldivOp(3,0)
  mdu_in.bits.is_div := ctrl.muldivOp(4)
  mdu_in.bits.flush := dx_flush
  // mdu_in.bits.b2b   := mdu_b2b
  mdu_in.bits.b2b   := false.B

  // fence.i
  val dx_fencei    = ctrl.fencei
  val fencei_exec_done  = Wire(Bool())

  val flush_icache_done = Wire(Bool())
  val icahce_flush_req  = dx_valid && !flush_icache_done && !dx_flush && dx_fencei && !dx_stall
  val flushi_done_set   = io.icache_req.fire
  val flushi_done_clr   = dx_valid && fencei_exec_done &&  wb_ready | dx_flush
  val flushi_done_n     = flushi_done_set | ~flushi_done_clr
  val flushi_done_en    = flushi_done_set | flushi_done_clr
  flush_icache_done     :=  RegEnable(flushi_done_n,  false.B, flushi_done_en)

  val clean_dcache_done = Wire(Bool())
  val dcache_clean_req  = dx_valid && !clean_dcache_done && !dx_flush && dx_fencei && !dx_stall
  val cleand_done_set   = io.dcache_req.fire
  val cleand_done_clr   = dx_valid && fencei_exec_done && wb_ready | dx_flush
  val cleand_done_n     = cleand_done_set | ~cleand_done_clr
  val cleand_done_en    = cleand_done_set | cleand_done_clr
  clean_dcache_done     := RegEnable(cleand_done_n, false.B, cleand_done_en)

  fencei_exec_done      := flush_icache_done & clean_dcache_done


  io.icache_req.valid   := icahce_flush_req
  io.icache_req.bits.invalid := dx_fencei
  io.icache_req.bits.clean   := false.B

  io.dcache_req.valid   := dcache_clean_req
  io.dcache_req.bits.invalid := false.B
  io.dcache_req.bits.clean   := dx_fencei

  io.mem.valid := dx_valid && !dx_exec_done && !dx_flush && is_mem && !dx_stall
  io.mem.bits.addr := alu.io.misc_req.res
  io.mem.bits.memType := ctrl.memType
  io.mem.bits.wdata := io.rs2Value

  val fencei_exec    = dx_fencei
  val multicycl_exec = is_mem | op_is_mdu
  val singlcycl_exec = ~multicycl_exec & ~fencei_exec

  val ready_go = Mux1H(Seq(
    multicycl_exec -> (fu_hsked | dx_exec_done),
    singlcycl_exec -> true.B,
    fencei_exec    -> fencei_exec_done
  )) & !dx_stall
  io.pplIn.ready := !dx_valid  || dx_flush || ready_go && io.pplOut.ready

  val wb_valid_n  =  if(WB_PIPESTAGE) (
      dx_valid && ready_go && !dx_flush
   ) else (
      dx_valid
   )

  // dontTouch(ready_go)
  // dontTouch(fu_hsked)

  val is_c_instr = io.pplIn.bits.isCInst

  val branch_jal    = jType(8)
  val branch_beq    = jType(7)
  val branch_bne    = jType(6)
  val branch_bge    = jType(5)
  val branch_bgeu   = jType(4)
  val branch_blt    = jType(3)
  val branch_bltu   = jType(2)
  val branch_jalr   = jType(1)
  val branch_bxx    = jType(0)

  val alu_eq        = alu.io.misc_req.eq  
  val alu_lt        = alu.io.misc_req.lt

  val branch_prdt_taken = io.pplIn.bits.prdt_taken
  val branch_real_taken = Mux1H(Seq(
    (branch_jal | branch_jalr)  -> true.B,
    branch_beq                -> alu_eq,
    branch_bne                -> !alu_eq,
    (branch_bge | branch_bgeu)  -> (!alu_lt | alu_eq),
    (branch_blt | branch_bltu)  -> alu_lt, 
    branch_bxx                -> false.B
  ))

  val uncondi_jump    = branch_jal | branch_jalr
  val condi_branch    = branch_beq | branch_bne | branch_bge | branch_bgeu | branch_blt | branch_bltu
  val is_branch       = uncondi_jump | condi_branch
  val branch_mis_prdt = (branch_prdt_taken ^ branch_real_taken) & is_branch | dx_fencei
  val flush_pc        = Mux(branch_jalr & branch_real_taken, io.rs1Value, pc)
  val bjp_pc_ofst   = Mux1H(Seq(
    branch_jal   ->  Jimm,
    branch_jalr  ->  Iimm,
    condi_branch ->  Bimm
  ))
  instr_size    := Mux(is_c_instr, 2.U, 4.U)
  val flush_pc_ofst = Mux(branch_real_taken, bjp_pc_ofst, instr_size)

  brchmis_slv := branch_mis_prdt
  dontTouch(branch_real_taken)
  dontTouch(branch_mis_prdt)

  brchmis_flush_pluse     := branch_mis_prdt & dx_valid & !dx_exec_done & !dx_flush & !dx_stall
  io.toifu.flush_req      := brchmis_flush_pluse
  io.toifu.flush_pc       := flush_pc
  io.toifu.flush_pc_ofst  := flush_pc_ofst
  io.toifu.decmuldiv_info := ctrl.muldivOp

  val dx_may_x1wen = (rd === 1.U) & dx_valid
  io.wen_x1        := dx_may_x1wen

  val op_is_csrread = csrRden
  val op_is_other   = ~op_is_mdu & ~op_is_csrread

  val pplInfo = Wire(new IDEX2WBSignal())

  pplInfo.rdWdat := Mux1H(Seq(
                    op_is_mdu -> mdu_out.bits, 
                    op_is_csrread -> io.csrData,
                    op_is_other   -> alu.io.misc_req.res
                  ))
  pplInfo.privInstr := priv_dec(inst)
  pplInfo.csrWen := csrWren
  pplInfo.csrWdat := Mux1H(Seq(
                     isCSRRW              -> Mux(isCSRI, csrImm, io.rs1Value),
                     (isCSRRC | isCSRRS)  -> alu.io.misc_req.res)
  )
  pplInfo.pc := io.pplIn.bits.pc
  pplInfo.inst   := inst
  pplInfo.wbType := ctrl.wbType
  pplInfo.wen := rd_wen
  pplInfo.memType := ctrl.memType
  pplInfo.isCInst := io.pplIn.bits.isCInst
  pplInfo.cInst := io.pplIn.bits.cInst
  pplInfo.isTrap := false.B
  pplInfo.trapCode := 0.U
  pplInfo.dx_ready := ready_go

  io.pplOut.valid := wb_valid_n
  io.pplOut.bits := pplInfo
  io.valid := dx_valid

  io.dxu_halt_ack := true.B

  if(MDU_CKG_EN) {
    val mdu_ck_en = BoringUtils.bore(mdu.mdu_ck_en)
    val mdu_clk = ClockGate(clock, mdu_ck_en)
    mdu.clock := mdu_clk
  }

    val bpu_statistics = false

    if(bpu_statistics) {
        val bpu_cnt   = RegInit(0.U(32.W))
        val hit_cnt   = RegInit(0.U(32.W))
        bpu_cnt := Mux(wb_valid_n & wb_ready & is_branch, bpu_cnt + 1.U, bpu_cnt)
        hit_cnt := Mux(!branch_mis_prdt & wb_valid_n & wb_ready & is_branch, hit_cnt + 1.U, hit_cnt)
        // when(!branch_mis_prdt & dx_valid & !dx_exec_done & !dx_flush & is_branch) {
        //   printf("BPU: HIT PC %x, inst %x\n", pc, inst)
        // }
        
        class BpuStat extends BlackBox with HasBlackBoxInline {
            val io = IO(new Bundle {
                val all_cnt = Input(UInt(32.W))
                val hit_cnt = Input(UInt(32.W))
            })
            setInline("BpuStat.v",
            """module BpuStat(
              | input [31:0] all_cnt,
              | input [31:0] hit_cnt
              |);
              |final begin
              |  $display("--- BPU STATISTICS ---");
              |  $display("Total Branch: %d", all_cnt);
              |  $display("Branch Hits:  %d", hit_cnt);
              |  if (all_cnt > 0) begin
              |    $display("Hit Rate:     %d %%", (hit_cnt * 100) / all_cnt);
              |  end
              |end
              |endmodule
              |""".stripMargin)
        }

        val Bpustat = Module(new BpuStat)
        Bpustat.io.all_cnt := bpu_cnt
        Bpustat.io.hit_cnt := hit_cnt
    }

}
