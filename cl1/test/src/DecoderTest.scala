package cl1

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import cl1.Control._

//TODO: add test target to Makefile
class DecoderTest extends AnyFreeSpec with ChiselScalatestTester {
  "decoder test" in {
    test(new Cl2Decoder()) { dut =>
      dut.io.inst.poke("h00000413".U(32.W))
      dut.io.out.jType.expect(J_XXX)
      dut.io.out.illegal.expect(false.B)

      dut.io.inst.poke("h00000073".U(32.W))
      dut.io.out.csrType.expect(CSR_P)
      dut.io.out.illegal.expect(false.B)

      dut.io.inst.poke("hffffffff".U(32.W))
      dut.io.out.illegal.expect(true.B)
    }
  }
}

class IDEXIllegalInstructionTest extends AnyFreeSpec with ChiselScalatestTester {
  "illegal instruction enters the WB exception path without side effects" in {
    test(new Cl1IDEXStage()) { dut =>
      dut.io.pplIn.valid.poke(true.B)
      dut.io.pplIn.bits.pc.poke("h80000020".U)
      dut.io.pplIn.bits.inst.poke("hffffffff".U)
      dut.io.pplIn.bits.prdt_taken.poke(0.U)
      dut.io.pplIn.bits.cInst.poke(0.U)
      dut.io.pplIn.bits.isCInst.poke(false.B)
      dut.io.pplIn.bits.rvcIllegal.poke(false.B)
      dut.io.pplIn.bits.ifu_fetch_err.poke(false.B)
      dut.io.pplIn.bits.muldiv_b2b.poke(false.B)

      dut.io.pplOut.ready.poke(true.B)
      dut.io.mem.ready.poke(true.B)
      dut.io.rs1Value.poke(0.U)
      dut.io.rs2Value.poke(0.U)
      dut.io.csrData.poke(0.U)
      dut.io.stall.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.icache_req.ready.poke(true.B)
      dut.io.dcache_req.ready.poke(true.B)

      dut.io.pplOut.valid.expect(true.B)
      dut.io.pplOut.bits.isTrap.expect(true.B)
      dut.io.pplOut.bits.trapCode.expect(2.U)
      dut.io.pplOut.bits.wen.expect(false.B)
      dut.io.pplOut.bits.csrWen.expect(false.B)
      dut.io.mem.valid.expect(false.B)

      dut.io.pplIn.bits.inst.poke("h00200073".U)
      dut.io.pplOut.bits.isTrap.expect(true.B)
      dut.io.pplOut.bits.trapCode.expect(2.U)
      dut.io.pplOut.bits.wen.expect(false.B)
      dut.io.pplOut.bits.csrWen.expect(false.B)
      dut.io.mem.valid.expect(false.B)

      dut.io.pplIn.bits.inst.poke("h345020f3".U)
      dut.io.pplIn.bits.rvcIllegal.poke(false.B)
      dut.io.pplOut.bits.isTrap.expect(true.B)
      dut.io.pplOut.bits.trapCode.expect(2.U)
      dut.io.pplOut.bits.wen.expect(false.B)
      dut.io.pplOut.bits.csrWen.expect(false.B)
      dut.io.mem.valid.expect(false.B)

      dut.io.pplIn.bits.inst.poke("h00000013".U)
      dut.io.pplIn.bits.rvcIllegal.poke(true.B)
      dut.io.pplOut.bits.isTrap.expect(true.B)
      dut.io.pplOut.bits.trapCode.expect(2.U)
      dut.io.pplOut.bits.wen.expect(false.B)
      dut.io.pplOut.bits.csrWen.expect(false.B)
      dut.io.mem.valid.expect(false.B)
    }
  }
}

class EXCPIllegalInstructionTest extends AnyFreeSpec with ChiselScalatestTester {
  "illegal instruction trap reuses the normal exception redirect and CSR update path" in {
    test(new Cl1EXCP()) { dut =>
      dut.io.ext_irq.poke(false.B)
      dut.io.sft_irq.poke(false.B)
      dut.io.tmr_irq.poke(false.B)
      dut.io.ifu_halt_ack.poke(true.B)
      dut.io.dxu_halt_ack.poke(true.B)
      dut.io.icache_idle.poke(true.B)
      dut.io.dcache_idle.poke(true.B)

      dut.io.excp2Csr.meie.poke(false.B)
      dut.io.excp2Csr.msie.poke(false.B)
      dut.io.excp2Csr.mtie.poke(false.B)
      dut.io.excp2Csr.mie.poke(false.B)
      dut.io.excp2Csr.mepc.poke(0.U)
      dut.io.excp2Csr.mtvec.poke("h20000000".U)

      dut.io.dbg2excp.debug_mode.poke(false.B)
      dut.io.dbg2excp.debug_irq_mask.poke(false.B)
      dut.io.dbg2excp.ebrk_excp_en.poke(false.B)
      dut.io.dbg2excp.debug_take_req.poke(false.B)

      dut.io.wb2Excp.cmt_ecall.poke(false.B)
      dut.io.wb2Excp.cmt_mret.poke(false.B)
      dut.io.wb2Excp.cmt_wfi.poke(false.B)
      dut.io.wb2Excp.wb_valid.poke(true.B)
      dut.io.wb2Excp.wb_pc.poke("h80000020".U)
      dut.io.wb2Excp.memNoOutStanding.poke(true.B)
      dut.io.wb2Excp.excp_valid.poke(true.B)
      dut.io.wb2Excp.excp_code.poke(2.U)

      dut.io.flush.expect(true.B)
      dut.io.flush_pc.expect("h20000000".U)
      dut.io.flush_ofst.expect(0.U)
      dut.io.excp2Csr.cmt_epc_en.expect(true.B)
      dut.io.excp2Csr.cmt_epc_n.expect("h80000020".U)
      dut.io.excp2Csr.cmt_cause_en.expect(true.B)
      dut.io.excp2Csr.cmt_cause_n.expect(2.U)
    }
  }
}
