package cl1

import chisel3._
import chisel3.util._

import utils.SRAMIO

class SRAMCtrl(Size: Int = 8, DW: Int = 32) extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(new SimpleBus(32))
        val out = Flipped(new SRAMIO(log2Ceil(Size) + 10 - log2Ceil(DW/8), DW))
    })

    val in = io.in
    val out = io.out

    val rw_vld_set = in.req.fire
    val rw_vld_clr = in.rsp.fire
    val rw_vld_en  = rw_vld_set | rw_vld_clr
    val rw_vld_n   = rw_vld_set | ~rw_vld_clr
    val rw_vld = RegEnable(rw_vld_n, false.B, rw_vld_en)

    out.A       := in.req.bits.addr(log2Ceil(Size) + 10 -1,2)
    out.D       := in.req.bits.wdata
    out.CEN     := !in.req.fire
    out.WEN     := !in.req.bits.wen
    out.BWEN    := ~Cat((0 until 4).map(i => Fill(8,in.req.bits.mask(i))).reverse)

    in.rsp.bits.rdata := out.Q
    in.rsp.bits.err   := 0.U

    in.req.ready := !rw_vld | rw_vld_clr
    in.rsp.valid := rw_vld
}