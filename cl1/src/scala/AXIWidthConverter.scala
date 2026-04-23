//32 to 64
package cl1

import chisel3._
import chisel3.util._
import AXIValue._


class AXIWidthConverter extends Module {
    val io = IO(new Bundle {
    val in  = Flipped(new AXI4(32, 32, 4))
    val out = new AXI4(32, 64, 4)
  })

  io.out.aw <> io.in.aw 

  val hword_r = RegInit(false.B)
  val wburst  = (io.in.aw.bits.awburst === AX_INCR)
  when(io.out.aw.fire & io.out.w.fire & wburst) {
    hword_r := ~io.out.aw.bits.awaddr(2)
  }.elsewhen(io.out.aw.fire & wburst) {
    hword_r := io.out.aw.bits.awaddr(2)
  }.elsewhen(io.out.w.fire) {
    hword_r := ~hword_r
  }.otherwise{
    hword_r := hword_r
  }

  val hword_flag = Mux(io.out.aw.fire, io.out.aw.bits.awaddr(2),hword_r)

  val zero = WireDefault(0.U(32.W))
  val wdata = io.in.w.bits.wdata
  val wstrb = io.in.w.bits.wstrb

  io.out.w.valid      := io.in.w.valid
  io.out.w.bits.wdata := Mux(hword_flag, Cat(wdata, zero), Cat(zero, wdata))
  io.out.w.bits.wlast := io.in.w.bits.wlast
  io.out.w.bits.wstrb := Mux(hword_flag, Cat(wstrb, 0.U(4.W)), Cat(0.U(4.W), wstrb))
  io.in.w.ready       := io.out.w.ready

  io.out.b.ready     := io.in.b.ready
  io.in.b.bits.bresp := io.out.b.bits.bresp
  io.in.b.bits.bid   := io.out.b.bits.bid
  io.in.b.valid      := io.out.b.valid


  io.out.ar <> io.in.ar 

  val rhword_r = RegInit(false.B)
  val rburst   = io.in.ar.bits.arburst === AX_INCR
  when(io.out.ar.fire & io.out.r.fire & rburst) {
    rhword_r := ~io.out.ar.bits.araddr(2)
  }.elsewhen(io.out.ar.fire & rburst) {
    rhword_r := ~io.out.ar.bits.araddr(2)
  }.elsewhen(io.out.w.fire) {
    rhword_r := ~rhword_r
  }.otherwise {
    rhword_r := rhword_r
  }

  val rhword_flag = Mux(io.out.ar.fire, io.out.ar.bits.araddr(2), rhword_r)

  val rdata = io.out.r.bits.rdata

  io.out.r.ready     := io.in.r.ready
  io.in.r.bits.rdata := Mux(rhword_flag, rdata(63, 32), rdata(31, 0))
  io.in.r.bits.rresp := io.out.r.bits.rresp
  io.in.r.bits.rid   := io.out.r.bits.rid
  io.in.r.valid      := io.out.r.valid
  io.in.r.bits.rlast := io.out.r.bits.rlast


}

