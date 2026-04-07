package utils

import chisel3._
import chisel3.util._

class SRAMIO(AW:Int = 10, DW:Int = 32) extends Bundle {
    val A       = Input(UInt(AW.W))
    val D       = Input(UInt(DW.W))
    val CEN     = Input(Bool())
    val WEN     = Input(Bool())
    val BWEN    = Input(UInt(DW.W))
    val Q       = Output(UInt(DW.W))
}

class SRAMNative(Syn: Boolean = false, Size: Int = 8,DW: Int = 32) extends Module {
    val io = IO(new SRAMIO(log2Ceil(Size) + 10 - log2Ceil(DW/8), DW))

    val WordDepth: Int = (log2Ceil(Size) + 10 - log2Ceil(DW/8))

    if(Syn == false) {
        val mem = SyncReadMem(WordDepth * 1024, Vec(DW,Bool()))
        val wrDat = VecInit(io.D.asBools)
        when(!io.CEN & !io.WEN) {
            mem.write(io.A, wrDat, io.BWEN.asBools.map(!_))
        }

        io.Q := mem.read(io.A, !io.CEN & io.WEN).asUInt
    } else {
        val mem = Module(new S55NLLG1PH_X256Y4D32_BW(WordDepth,DW))
        mem.io.CLK := clock
        mem.io.A := io.A
        mem.io.D := io.D
        mem.io.CEN := io.CEN
        mem.io.WEN := io.WEN
        mem.io.BWEN := io.BWEN
        io.Q := mem.io.Q
    }

}

class S55NLLG1PH_X256Y4D32_BW(AW: Int = 10, DW: Int = 32) extends BlackBox {
    val io = IO(new Bundle {
        val CLK = Input(Clock())
        val A       = Input(UInt(AW.W))
        val D       = Input(UInt(DW.W))
        val CEN     = Input(Bool())
        val WEN     = Input(Bool())
        val BWEN    = Input(UInt(DW.W))
        val Q       = Output(UInt(DW.W))
    })
}