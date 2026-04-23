package utils

import chisel3._
import chisel3.util._
import cl1.Cl1Config._

class sramIO(val WordDepth:Int = 256, val DW: Int = 32, val BE: Boolean = false) extends Bundle {
    val addr = Input(UInt(log2Ceil(WordDepth).W))
    val din  = Input(UInt(DW.W))
    val wea  = Input(if (BE) UInt((DW/8).W) else Bool())
    val ena  = Input(Bool())
    val dout = Output(UInt(DW.W))
}

class sram(val WordDepth:Int = 256, val DW: Int = 32, val BE: Boolean = false) extends Module {
    val io = IO(new sramIO(WordDepth, DW, BE))
    val Syn = SramFoundary
    if(BE) {
        if(!Syn) {
            val mem = SyncReadMem(WordDepth, Vec(DW/8, UInt(8.W)))
            val dataAsVec = io.din.asTypeOf(Vec(DW/8, UInt(8.W)))
            when(io.ena && io.wea =/= 0.U) {
                mem.write(io.addr, dataAsVec, io.wea.asBools)
            }
            io.dout := mem.read(io.addr, io.ena).asUInt
        } else {
            require(DW == 32, s"The SRAM macro S55NLLG1PH_X128Y4D32_BW only supports a data width (DW) of 32, but got ${DW}.")
            val SMICSram = if (Technology == "SMIC110")
                                { Module(new S011HD1P_X64Y2D32_BW(log2Ceil(WordDepth), DW)) }
                           else 
                                { Module(new S55NLLG1PH_X128Y1D32_BW(log2Ceil(WordDepth), DW)) }

            SMICSram.io.CLK := clock
            SMICSram.io.A := io.addr
            SMICSram.io.D := io.din
            SMICSram.io.CEN := !io.ena
            SMICSram.io.WEN := !(io.wea =/= 0.U)
            SMICSram.io.BWEN.foreach(_ := Cat(io.wea.asBools.map(b => Fill(8,!b)).reverse))
            io.dout := SMICSram.io.Q
        }
    } else {
        if(!Syn) {
            val mem = SyncReadMem(WordDepth, UInt(DW.W))
            when(io.ena && io.wea =/= 0.U) {
                mem.write(io.addr, io.din)
            }
            io.dout := mem.read(io.addr, io.ena)
        } else {
            require(DW == 32 || DW == 22, s"The SRAM macro S55NLLG1PH_X128Y4D32_BW only supports a data width (DW) of 32 or 22, but got ${DW}.")
            if (DW == 32) {
                val SMICSram = if( Technology == "SMIC110")
                                    { Module(new S011HD1P_X64Y2D32(log2Ceil(WordDepth), DW)) }
                               else 
                                    { Module(new S55NLLG1PH_X128Y1D32(log2Ceil(WordDepth), DW)) }
                SMICSram.io.CLK := clock
                SMICSram.io.A := io.addr
                SMICSram.io.D := io.din
                SMICSram.io.CEN := !io.ena
                SMICSram.io.WEN := !(io.wea =/= 0.U)
                io.dout := SMICSram.io.Q
            } else {
                val SMICSram = if( Technology == "SMIC110")
                                    { Module(new S011HD1P_X64Y2D22(log2Ceil(WordDepth), DW)) }
                               else 
                                    { Module(new S55NLLG1PH_X128Y1D22(log2Ceil(WordDepth), DW)) }
                SMICSram.io.CLK := clock
                SMICSram.io.A := io.addr
                SMICSram.io.D := io.din
                SMICSram.io.CEN := !io.ena
                SMICSram.io.WEN := !(io.wea =/= 0.U)
                io.dout := SMICSram.io.Q
            }
        }
    }
}

class SMICSramIO(AW: Int = 8, DW: Int = 31, hasBWEN: Boolean = false) extends Bundle {
    val CLK     = Input(Clock())
    val A       = Input(UInt(AW.W))
    val D       = Input(UInt(DW.W))
    val CEN     = Input(Bool())
    val WEN     = Input(Bool())
    val BWEN    = if (hasBWEN) Some(Input(UInt(DW.W))) else None
    val Q       = Output(UInt(DW.W))
}

abstract class SMICSramBlackBoxBase(name: String, AW: Int, DW: Int, hasBWEN: Boolean = false) extends BlackBox {
    val io = IO(new SMICSramIO(AW, DW, hasBWEN))
    override def desiredName: String = name
}


class S55NLLG1PH_X128Y1D32_BW(AW: Int = 10, DW: Int = 32)
    extends SMICSramBlackBoxBase("S55NLLG1PH_X128Y1D32_BW", AW, DW, hasBWEN = true) 

class S55NLLG1PH_X128Y1D32(AW: Int = 10, DW: Int = 32) 
    extends SMICSramBlackBoxBase("S55NLLG1PH_X128Y1D32", AW, DW, hasBWEN = false)
class S55NLLG1PH_X128Y1D22(AW: Int = 10, DW: Int = 21)
    extends SMICSramBlackBoxBase("S55NLLG1PH_X128Y1D22", AW, DW, hasBWEN = false)
class S011HD1P_X64Y2D22(AW: Int = 10, DW: Int = 22)
    extends SMICSramBlackBoxBase("S011HD1P_X64Y2D22", AW, DW, hasBWEN = false)
class S011HD1P_X64Y2D32(AW: Int = 10, DW: Int = 32) 
    extends SMICSramBlackBoxBase("S011HD1P_X64Y2D32", AW, DW, hasBWEN = false)
class S011HD1P_X64Y2D32_BW(AW: Int = 10, DW: Int = 32)
    extends SMICSramBlackBoxBase("S011HD1P_X64Y2D32_BW", AW, DW, hasBWEN = true)