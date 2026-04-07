package cl1

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import cl1.Control._

//TODO: add test target to Makefile
class DecoderTest extends AnyFreeSpec {
  "docoder test" in {
    simulate(new Cl2Decoder()) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      dut.io.inst.poke("h00000413".U(32.W))
      dut.io.out.jType.expect(J_XXX)
      dut.io.inst.poke("h00000073".U(32.W))
      dut.io.out.csrType.expect(CSR_P)

    }
  }
  "sra test" in {
    simulate(new Cl2ALU()) {
      dut =>
        dut.io.a.poke("h80000000".U)
        dut.io.b.poke(8.U)
        dut.io.op.poke(ALU_SRA)
        dut.io.res.expect("hff800000".U)
    }
  }
  "slt test" in {
    simulate(new Cl2ALU()) {
      dut =>
        dut.io.a.poke("hfffffdff".U)
        dut.io.b.poke("h00000005".U)
        dut.io.op.poke(ALU_SLT)
        dut.io.res.expect("h1".U)

    }
  }
}

class CSRTest extends AnyFreeSpec {
  "ecall test" in {
    simulate(new Cl2CSR()) {
      dut => 
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        dut.io.rdAddr.poke(0.U)
        dut.io.wrAddr.poke(0.U)
        dut.io.wrValue.poke(0.U)
        dut.io.wen.poke(false.B)
        dut.io.cause.poke(11.U)
        dut.io.trap.poke(true.B)
        dut.io.pc.poke("h80000000".U)
        dut.io.instr.poke(0.U)
        dut.io.c_instr.poke(0.U)

        dut.clock.step()
        dut.io.epc_addr.expect("h80000000".U)

    }
  }
}