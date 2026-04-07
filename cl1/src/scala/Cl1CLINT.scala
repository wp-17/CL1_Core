package cl1

import chisel3._
import chisel3.util._


class Cl1CLINT extends Module {
    
    val io = IO(new Bundle {
        val req = Flipped(Decoupled(Input(new SimpleMemReq(32))))
        val resp = Decoupled(Output(new SimpleMemResp(32)))
    })


    val testReg = RegInit("h1212121".U)

    val idle :: active :: Nil = Enum(2)
    val state = RegInit(idle)

    switch(state) {
        is(idle) {
            when(io.req.fire) {
                state := active
            }
        }
        is(active) {
            when(io.resp.fire && !io.req.fire) {
                state := idle
            }
        }
    }

    io.req.ready := true.B
    io.resp.valid := state === active
    io.resp.bits.rdata := testReg
    io.resp.bits.err := 0.U

    



}