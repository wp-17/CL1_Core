// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._

object SimpleMask {
  val MASK_Z   = "b0000".U(4.W)
  val MASK_ALL = "b1111".U(4.W) 
  val MASK_HI  = "b1100".U(4.W)
  val MASK_LO  = "b0011".U(4.W)
  val MASK_B0  = "b0001".U(4.W)
  val MASK_B1  = "b0010".U(4.W)
  val MASK_B2  = "b0100".U(4.W)
  val MASK_B3  = "b1000".U(4.W)
}

class SimpleMemReq(xlen: Int) extends Bundle {
  val addr  = UInt(xlen.W)
  val wen   = Bool()
  val size  = UInt(3.W) //3 bits in AXI4
  val wdata = UInt(xlen.W)
  val mask  = UInt(4.W)
  val cacheable = Bool()
}

class SimpleMemResp(xlen: Int) extends Bundle {
  val err   = UInt(2.W)
  val rdata = UInt(xlen.W)
}

class SimpleBus(xlen: Int) extends Bundle {
  val req  = Decoupled(Output(new SimpleMemReq(xlen)))
  val rsp  = Flipped(Decoupled(Output(new SimpleMemResp(xlen))))
}

class SimpleBusCut extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new SimpleBus(32))
    val out = new SimpleBus(32)
  })

  io.out.req <> SpillReg(io.in.req)
  io.in.rsp <> SpillReg(io.out.rsp)
}

class CacheBusCut extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new CacheBus())
    val out = new CacheBus()
  })

  io.out.req <> SpillReg(io.in.req)
  io.in.rsp <> SpillReg(io.out.rsp)
}

class Simple2CoreBus extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SimpleBus(32))
    val out = new CoreBus
  })

  io.out.req.valid        := io.in.req.valid
  io.out.req.bits.addr    := io.in.req.bits.addr
  io.out.req.bits.data    := io.in.req.bits.wdata
  io.out.req.bits.wen     := io.in.req.bits.wen
  io.out.req.bits.mask    := io.in.req.bits.mask
  io.out.req.bits.cache   := io.in.req.bits.cacheable
  io.out.req.bits.size    := io.in.req.bits.size
  io.in.req.ready         := io.out.req.ready

  io.in.rsp.valid         := io.out.rsp.valid
  io.in.rsp.bits.rdata    := io.out.rsp.bits.data
  io.in.rsp.bits.err      := io.out.rsp.bits.err
  io.out.rsp.ready        := io.in.rsp.ready
}

class CoreBus2CacheBus extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new CoreBus)
    val out = new CacheBus
  })

    io.out.req.valid      := io.in.req.valid
    io.out.req.bits.addr  := io.in.req.bits.addr
    io.out.req.bits.data  := io.in.req.bits.data
    io.out.req.bits.wen   := io.in.req.bits.wen
    io.out.req.bits.burst := false.B
    io.out.req.bits.mask  := io.in.req.bits.mask
    io.out.req.bits.len   := 0.U
    io.out.req.bits.size  := io.in.req.bits.size
    io.out.req.bits.last  := io.in.req.valid
    io.in.req.ready       := io.out.req.ready

    io.in.rsp.valid       := io.out.rsp.valid
    io.in.rsp.bits.data   := io.out.rsp.bits.data
    io.in.rsp.bits.err    := io.out.rsp.bits.err
    io.out.rsp.ready      := io.in.rsp.ready
  
}