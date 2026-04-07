// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._

class RVFI extends Bundle {
  val rvfi_valid     = Output(Bool())
  val rvfi_order     = Output(UInt(64.W))
  val rvfi_insn      = Output(UInt(32.W))
  val rvfi_trap      = Output(Bool())
  val rvfi_halt      = Output(Bool())
  val rvfi_intr      = Output(Bool())
  val rvfi_mode      = Output(UInt(2.W))
  val rvfi_ixl       = Output(UInt(2.W))
  val rvfi_rs1_addr  = Output(UInt(5.W))
  val rvfi_rs2_addr  = Output(UInt(5.W))
  val rvfi_rs1_rdata = Output(UInt(32.W))
  val rvfi_rs2_rdata = Output(UInt(32.W))
  val rvfi_rd_addr   = Output(UInt(5.W))
  val rvfi_rd_wdata  = Output(UInt(32.W))
  val rvfi_pc_rdata  = Output(UInt(32.W))
  val rvfi_pc_wdata  = Output(UInt(32.W))
  val rvfi_mem_addr  = Output(UInt(32.W))
  val rvfi_mem_rmask = Output(UInt(4.W))
  val rvfi_mem_wmask = Output(UInt(4.W))
  val rvfi_mem_rdata = Output(UInt(32.W))
  val rvfi_mem_wdata = Output(UInt(32.W))
}
