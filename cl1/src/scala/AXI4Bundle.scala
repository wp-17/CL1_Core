// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._

object AXIValue {
  val AX_FIXED = 0.U
  val AX_INCR  = 1.U
  val AX_WRAP  = 2.U
}
class AXI4(ADDR_WIDTH: Int, DATA_WIDTH: Int, ID_WIDTH: Int) extends Bundle {

  val aw = Decoupled(new Bundle {
    val awaddr  = Output(UInt(ADDR_WIDTH.W))
    val awid    = Output(UInt(ID_WIDTH.W))
    val awlen   = Output(UInt(8.W))
    val awsize  = Output(UInt(3.W))
    val awburst = Output(UInt(2.W))
    val awlock  = Output(UInt(1.W))
    val awcache = Output(UInt(4.W))
    val awprot  = Output(UInt(3.W))
  })

  val w = Decoupled(new Bundle {
    val wdata  = Output(UInt(DATA_WIDTH.W))
    val wstrb  = Output(UInt((DATA_WIDTH/8).W))
    val wlast  = Output(Bool())
  })

  val b = Flipped(Decoupled(new Bundle {
    val bresp  = Input(UInt(2.W))
    val bid    = Input(UInt(ID_WIDTH.W))

  }))

  val ar = Decoupled(new Bundle {
    val araddr  = Output(UInt(ADDR_WIDTH.W))
    val arid    = Output(UInt(ID_WIDTH.W))
    val arlen   = Output(UInt(8.W))
    val arsize  = Output(UInt(3.W))
    val arburst = Output(UInt(2.W))
    val arlock  = Output(UInt(1.W))
    val arcache = Output(UInt(4.W))
    val arprot  = Output(UInt(3.W))
  })

  val r = Flipped(Decoupled(new Bundle {
    val rresp  = Input(UInt(2.W))
    val rdata  = Input(UInt(DATA_WIDTH.W))
    val rlast  = Input(Bool())
    val rid    = Input(UInt(ID_WIDTH.W))

  }))
}


class AXICut extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(new AXI4(32, 32, 2))
        val out = new AXI4(32, 32, 2)
    })

    io.out.aw :=  SpillReg(io.in.aw)
    io.out.w  :=  SpillReg(io.in.w )
    io.in.b   :=  SpillReg(io.out.b)
    io.out.ar :=  SpillReg(io.in.ar)
    io.in.r   :=  SpillReg(io.out.r)
}


class SimpleBus2Axi4 extends Module {
    val io = IO(new Bundle{
        val in  = Flipped(new SimpleBus(32))
        val out = new AXI4(32, 32, 2)
    })

    io.out.aw.valid        := io.in.req.valid & io.in.req.bits.wen & io.out.w.ready
    io.out.aw.bits.awaddr  := io.in.req.bits.addr
    io.out.aw.bits.awid    := 0.U
    io.out.aw.bits.awlen   := 0.U
    io.out.aw.bits.awsize  := io.in.req.bits.size
    io.out.aw.bits.awburst := AXIValue.AX_INCR
    io.out.aw.bits.awlock  := 0.U
    io.out.aw.bits.awcache := io.in.req.bits.cacheable
    io.out.aw.bits.awprot  := 0.U

    io.out.w.valid         := io.in.req.valid & io.in.req.bits.wen & io.out.aw.ready
    io.out.w.bits.wdata    := io.in.req.bits.wdata
    io.out.w.bits.wstrb    := io.in.req.bits.mask
    io.out.w.bits.wlast    := true.B

    io.out.ar.valid        := io.in.req.valid & ~io.in.req.bits.wen
    io.out.ar.bits.araddr  := io.in.req.bits.addr
    io.out.ar.bits.arid    := 0.U
    io.out.ar.bits.arlen   := 0.U
    io.out.ar.bits.arsize  := io.in.req.bits.size
    io.out.ar.bits.arburst := AXIValue.AX_INCR
    io.out.ar.bits.arlock  := 0.U
    io.out.ar.bits.arcache := io.in.req.bits.cacheable
    io.out.ar.bits.arprot  := 0.U
    // io.in.req.ready        := io.out.ar.ready
    
    io.in.req.ready        := Mux(io.in.req.bits.wen, io.out.aw.ready & io.out.w.ready, io.out.ar.ready)

    val rsp_w              = RegEnable(io.in.req.bits.wen, false.B, io.in.req.fire)

    io.in.rsp.valid        := Mux(rsp_w, io.out.b.valid, io.out.r.valid)
    io.in.rsp.bits.err     := Mux(rsp_w, io.out.b.bits.bresp, io.out.r.bits.rresp)
    io.in.rsp.bits.rdata   := io.out.r.bits.rdata

    io.out.b.ready         := io.in.rsp.ready & rsp_w
    io.out.r.ready         := io.in.rsp.ready & ~rsp_w

}

class CacheBus2Axi4 extends Module {
    val io = IO(new Bundle{
      val in  = Flipped(new CacheBus())
      val out = new AXI4(32, 32, 2)
    })

    // Fall through when idle, but keep any AXI channel that did not handshake.
    val reqBuf             = Reg(chiselTypeOf(io.in.req.bits))
    val reqBufValid        = RegInit(false.B)
    val pendAw             = RegInit(false.B)
    val pendW              = RegInit(false.B)
    val pendAr             = RegInit(false.B)
    val writeBurstActive   = RegInit(false.B)

    val srcBits            = Mux(reqBufValid, reqBuf, io.in.req.bits)
    val srcValid           = reqBufValid | io.in.req.valid
    val needAw             = Mux(reqBufValid, pendAw, io.in.req.bits.wen & ~writeBurstActive)
    val needW              = Mux(reqBufValid, pendW, io.in.req.bits.wen)
    val needAr             = Mux(reqBufValid, pendAr, ~io.in.req.bits.wen)
    val byteOffset         = PriorityEncoder(srcBits.mask)
    val axiAddr            = srcBits.addr | byteOffset

    io.out.aw.valid         := srcValid & needAw
    io.out.aw.bits.awaddr   := axiAddr
    io.out.aw.bits.awid     := 0.U
    io.out.aw.bits.awlen    := srcBits.len
    io.out.aw.bits.awsize   := srcBits.size
    io.out.aw.bits.awburst  := AXIValue.AX_INCR
    io.out.aw.bits.awcache  := 0.U
    io.out.aw.bits.awprot   := 0.U
    io.out.aw.bits.awlock   := 0.U

    io.out.w.valid          := srcValid & needW
    io.out.w.bits.wdata     := srcBits.data
    io.out.w.bits.wstrb     := srcBits.mask
    io.out.w.bits.wlast     := srcBits.last

    io.out.ar.valid         := srcValid & needAr
    io.out.ar.bits.araddr   := axiAddr
    io.out.ar.bits.arid     := 0.U
    io.out.ar.bits.arlen    := srcBits.len
    io.out.ar.bits.arsize   := srcBits.size
    io.out.ar.bits.arburst  := AXIValue.AX_INCR
    io.out.ar.bits.arlock   := 0.U
    io.out.ar.bits.arcache  := 0.U
    io.out.ar.bits.arprot   := 0.U

    val awLeft             = srcValid & needAw & ~io.out.aw.fire
    val wLeft              = srcValid & needW & ~io.out.w.fire
    val arLeft             = srcValid & needAr & ~io.out.ar.fire
    val reqDone            = srcValid & ~awLeft & ~wLeft & ~arLeft
    val reqProgress        = needAw & io.out.aw.fire |
                             needW  & io.out.w.fire  |
                             needAr & io.out.ar.fire

    io.in.req.ready        := ~reqBufValid & (!io.in.req.valid | reqProgress)

    when (io.in.req.fire) {
      when (!reqDone) {
        reqBuf             := io.in.req.bits
        reqBufValid        := true.B
        pendAw             := awLeft
        pendW              := wLeft
        pendAr             := arLeft
      }
      writeBurstActive     := io.in.req.bits.wen & ~io.in.req.bits.last
    } .elsewhen (reqBufValid) {
      reqBufValid          := ~reqDone
      pendAw               := awLeft
      pendW                := wLeft
      pendAr               := arLeft
    }

    val rsp_w              = RegInit(false.B)
    when (io.in.req.fire & io.in.req.bits.last) {
      rsp_w := io.in.req.bits.wen
    }
    io.in.rsp.valid         := Mux(rsp_w, io.out.b.valid, io.out.r.valid)
    io.in.rsp.bits.err      := Mux(rsp_w, io.out.b.bits.bresp, io.out.r.bits.rresp)
    io.in.rsp.bits.data     := io.out.r.bits.rdata
    io.in.rsp.bits.last     := io.out.r.bits.rlast | io.out.b.fire

    io.out.b.ready         := io.in.rsp.ready & rsp_w
    io.out.r.ready         := io.in.rsp.ready & ~rsp_w
}
