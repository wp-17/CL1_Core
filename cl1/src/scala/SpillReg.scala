package cl1
import chisel3._
import chisel3.util._

object SpillReg {
    def apply[T <: Data](   in:         DecoupledIO[T], 
                            bypass:     Boolean = false, 
                            cutReady:   Boolean = true,
                            pingpong:   Boolean = false
                        ): DecoupledIO[T] = {
                            if(bypass == true) {
                                val out = Wire(new DecoupledIO(chiselTypeOf(in.bits)))
                                out.valid := in.valid
                                out.bits  := in.bits
                                in.ready  := out.ready
                                out
                            } else if(pingpong == true) {
                                val out = Wire(new DecoupledIO(chiselTypeOf(in.bits)))
                                val b_vld     = Wire(Bool())
                                val a_vld_set = in.fire
                                val a_vld_clr = ~b_vld | out.fire
                                val a_vld_en  = a_vld_set | a_vld_clr
                                val a_vld_n   = a_vld_set | ~a_vld_clr
                                val a_vld     = RegEnable(a_vld_n, false.B, a_vld_en)
                                val a_data    = RegEnable(in.bits, 0.U,     a_vld_set)

                                val b_vld_set = a_vld & ~out.ready
                                val b_vld_clr = out.fire
                                val b_vld_en  = b_vld_set | b_vld_clr
                                val b_vld_n   = b_vld_set | ~b_vld_clr
                                b_vld         := RegEnable(b_vld_n, false.B, b_vld_en)
                                val b_data    = RegEnable(a_data, 0.U.asTypeOf(in.bits),      b_vld_set)

                                in.ready  := ~a_vld | ~b_vld
                                out.valid := Mux(b_vld, b_vld, a_vld)
                                out.bits  := Mux(b_vld, b_data, a_data)
                                out
                            } else {
                                val out = Wire(new DecoupledIO(chiselTypeOf(in.bits)))
                                val vld_set = in.fire
                                val vld_clr = out.fire
                                val vld_en  = vld_set | vld_clr
                                val vld_n   = vld_set | ~vld_clr
                                val vld     = RegEnable(vld_n, false.B, vld_en)
                                val data    = RegEnable(in.bits, 0.U.asTypeOf(in.bits),   vld_set)
                                
                                out.valid := vld
                                out.bits  := data
                                in.ready  := (if (cutReady == true) {~vld } else { ~vld | vld_clr})
                                out
                            }
                        }
}

object BypReg {
    def apply[T <: Data] (
        in: DecoupledIO[T]
    ): DecoupledIO[T] = {
        val out = Wire(new DecoupledIO(chiselTypeOf(in.bits)))
        val byp = Wire(Bool())
        val bypVld = Wire(Bool())
        val bypVld_set = in.valid & ~byp & ~bypVld
        val bypVld_clr = bypVld & out.ready
        val bypVld_en  = bypVld_set | bypVld_clr
        val bypVld_n   = bypVld_set | ~bypVld_clr
        bypVld         := RegEnable(bypVld_n, false.B, bypVld_en)
        val bypDat = RegEnable(in.bits, bypVld_set)

        byp := in.valid & out.ready & ~bypVld

        in.ready  := ~bypVld 
        out.valid := in.valid | bypVld
        out.bits  := Mux(bypVld, bypDat, in.bits)
        out
    }
}