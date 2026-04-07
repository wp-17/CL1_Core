package utils 

import chisel3._
import chisel3.util._

object PipelineConnect {
    def apply[T <: Data](left:  DecoupledIO[T], right: DecoupledIO[T], isFlush: Bool) = {
        val valid = RegInit(false.B)
        when (right.ready) { 
            valid := left.valid
        }
        when (isFlush) { valid := false.B}

        left.ready := right.ready
        right.bits := RegEnable(left.bits, left.valid && right.ready)
        right.valid := valid
    }
}