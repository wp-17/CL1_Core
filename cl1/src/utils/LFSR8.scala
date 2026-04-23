
package utils

import chisel3._
import chisel3.util._

object LFSR8 {
    def apply(increment: Bool = true.B): UInt = {
        val wide = 8
        val lfsr = RegInit(0x1.U(wide.W))
        val lfsr_n = Cat(lfsr(6), lfsr(5,3) ^ Fill(3, lfsr(7)), lfsr(2,0), lfsr(7))
        when(increment) {
            lfsr := lfsr_n
        }
        lfsr
    }
}