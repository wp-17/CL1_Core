// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._

import Control._
import cl1.SimpleMask._

class LSU2WBSignal extends Bundle {
  val rdata = Output(UInt(32.W))
  val err   = Output(UInt(4.W))
}

object SignExt {
  def apply(sig: UInt, len: Int): UInt = {
    val signBit = sig(sig.getWidth - 1)
    if (sig.getWidth >= len) sig(len - 1, 0) else signBit.asUInt ## Fill(len - sig.getWidth, signBit) ## sig
  }
}

object ZeroExt {
  def apply(sig: UInt, len: Int): UInt = {
    if (sig.getWidth >= len) sig(len - 1, 0) else 0.U((len - sig.getWidth).W) ## sig
  }
}

//See https://github.com/OpenXiangShan/Utility/blob/master/src/main/scala/utility/LookupTree.scala

class Cl1LSU extends Module {
  val io = IO(new Bundle {
    val in = new Bundle {
      val req = Flipped(Decoupled(Flipped(new IDEX2LSUSignal())))
      val resp = Decoupled(new LSU2WBSignal())
    }
    
    val out = new CoreBus()

    val flush = Input(Bool())
    // val misaligned = Input(Bool()) TODO:add misaligned memory access exception
  })

  val bypReq = io.in.req
  // val bypReq = BypReg(io.in.req)

  val s_freeze  = "b00".U(2.W)
  val s_idle    = "b01".U(2.W)
  val s_waiting = "b11".U(2.W)
  val s_drop    = "b10".U(2.W)
  val sFreeze :: sIdle :: sWaiting :: sDrop :: Nil = Enum(4)
  val state = RegInit(sFreeze)

  val stateFreeze : Bool = state === sFreeze
  val stateIdle   : Bool = state === sIdle
  val stateWaiting: Bool = state === sWaiting
  val stateDrop   : Bool = state === sDrop

  val req = bypReq
  val resp = io.in.resp

  switch(state) {
    is(s_freeze) {
      state := s_idle
    }
    is(s_idle) {
      when(req.fire) {
        state := s_waiting
      }
    }
    is(s_waiting) {
      when(io.flush && !resp.fire) {
        state := s_drop
      }.otherwise{
        when(req.fire ^ resp.fire) {
          state := s_idle
        }
      }
    }
    is(s_drop) {
      when(resp.fire) {
        state := s_idle
      }
    }
  }


  bypReq.ready := MuxLookup(state, false.B)(Seq(
    s_idle -> io.out.req.ready,
    s_waiting -> Mux(io.in.resp.fire, io.out.req.ready, false.B)
  ))

  io.in.resp.valid := state === s_waiting && io.out.rsp.valid
  io.out.req.valid := MuxLookup(state, false.B)(Seq(
    s_idle -> bypReq.valid,
    s_waiting -> Mux(io.in.resp.fire, bypReq.valid, false.B)
  ))
  io.out.rsp.ready := MuxLookup(state, false.B)(Seq(
    s_waiting -> io.in.resp.ready,
    s_drop -> true.B
  ))

  val addr = bypReq.bits.addr
  val width = bypReq.bits.memType(2, 1)
  val wen  = bypReq.bits.memType(3)

  io.out.req.bits.addr := addr
  val d_cached = if(globalConfig.simpleSocTest) SimpleSocMemoryMap.isDCacheable(addr) else MemoryMap.isDCacheable(addr)
  io.out.req.bits.cache := d_cached
  // io.out.req.bits.cache := false.B
  // io.out.req.bits.invalid := false.B
  //TODO: remove MuxLookup
  val mask = MuxLookup(Cat(addr(1, 0), width), MASK_Z)(Seq(
    // 1 byte
    "b0001".U ->  MASK_B0,
    "b0101".U ->  MASK_B1,
    "b1001".U ->  MASK_B2,
    "b1101".U ->  MASK_B3,
    // 2 bytes
    "b0010".U ->  MASK_LO,
    "b1010".U ->  MASK_HI,
    // 4 bytes
    "b0011".U ->  MASK_ALL
  ))
  io.out.req.bits.mask := mask
  io.out.req.bits.size := MuxLookup(width, 0.U)(Seq(
    1.U -> 0.U,
    2.U -> 1.U,
    3.U -> 2.U
  ))
  //TODO: use MuxLookupTree
  val wdata = bypReq.bits.wdata
  val one_byte_wdata = wdata(7, 0)
  val two_bytes_wdata = wdata(15, 0)
  io.out.req.bits.data := MuxLookup(mask, 0.U)(Seq(
    MASK_B0 -> wdata, //pay attention here
    MASK_B1 -> Cat(0.U(16.W), one_byte_wdata, 0.U(8.W)),
    MASK_B2 -> Cat(0.U(8.W), one_byte_wdata, 0.U(16.W)),
    MASK_B3 -> Cat(one_byte_wdata, 0.U(24.W)),
    MASK_LO -> wdata,
    MASK_HI -> Cat(two_bytes_wdata, 0.U(16.W)),
    MASK_ALL -> wdata
  ))
  io.out.req.bits.wen   := wen

  val req_buf = Reg(new Bundle {
    val memType = UInt(MEM_WIDTH.W) 
    val mask    = UInt(4.W)
  })
  when(bypReq.fire) {
    req_buf.memType := bypReq.bits.memType
    req_buf.mask    := mask
  }
  io.in.resp.bits.err := 0.U //TODO: add err
  val rdata = io.out.rsp.bits.data
  //TODO: optimize this !!!
  val one_byte_rdata = Mux1H(Seq(
    req_buf.mask(0) -> rdata(7, 0),
    req_buf.mask(1) -> rdata(15, 8),
    req_buf.mask(2) -> rdata(23, 16),
    req_buf.mask(3) -> rdata(31, 24)
  ))
  val two_bytes_rdata = Mux(req_buf.mask(1, 0) === "b11".U, rdata(15, 0), rdata(31, 16))
  io.in.resp.bits.rdata  := MuxLookup(req_buf.memType(2, 0), 0.U)(Seq(
    "b010".U -> SignExt(one_byte_rdata, 32),
    "b011".U -> ZeroExt(one_byte_rdata, 32),
    "b100".U -> SignExt(two_bytes_rdata, 32),
    "b101".U -> ZeroExt(two_bytes_rdata, 32),
    "b110".U -> rdata
  ))


  val lsu_ck_en = ~(stateIdle & ~req.valid)


}
