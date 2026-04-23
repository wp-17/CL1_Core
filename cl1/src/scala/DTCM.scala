package cl1

import chisel3._
import chisel3.util._

import utils.SRAMNative

class DTCM(Size: Int = 4, DW : Int = 32) extends Module {
    val Syn: Boolean = true
    val io = IO(Flipped(new SimpleBus(32)))

    val sramCtrl    = Module(new SRAMCtrl(Size, DW))
    val nativeSRAM  = Module(new SRAMNative(Syn, Size, DW))

    sramCtrl.io.in  <> io
    nativeSRAM.io   <> sramCtrl.io.out

}