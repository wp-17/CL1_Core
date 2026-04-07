// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import cl1.Cl1PowerSaveConfig._

class Cl1RegFile extends Module {
  val io = IO(new Bundle {
    val readAddrA = Input(UInt(5.W))
    val readAddrB = Input(UInt(5.W))
    val readDataA = Output(UInt(32.W))
    val readDataB = Output(UInt(32.W))
    val wen       = Input(Bool())
    val writeAddr = Input(UInt(5.W))
    val writeData = Input(UInt(32.W))
    val x1_val    = Output(UInt(32.W))
  })

  val regs = VecInit.tabulate(32) { i =>
    if (i == 0) {
      0.U(32.W)
    } else {
      if(RF_NORESET) {
        RegEnable(io.writeData, io.wen && (io.writeAddr === i.U))
      } else {
        RegEnable(io.writeData, 0.U(32.W), io.wen && (io.writeAddr === i.U))
      }
    }
  }

  io.readDataA := regs(io.readAddrA)
  io.readDataB := regs(io.readAddrB)
  io.x1_val    := regs(1.U)
}
