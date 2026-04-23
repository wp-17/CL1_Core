package cl1

import chisel3._
import chisel3.util._

class SimpleBusCrossbar1toN(adderSpace: List[(Long, Long)]) extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(new SimpleBus(32))
        val out = Vec(adderSpace.length, new SimpleBus(32))
    })

    val s_idle :: s_resp :: s_error :: Nil = Enum(3)
    val st_n    = WireInit(s_idle)
    val st_en   = WireInit(false.B)
    val st      = RegEnable(st_n, s_idle, st_en)

    val addr    = io.in.req.bits.addr
    val outMatchVec = VecInit(adderSpace.map(
        range => (addr >= range._1.U && addr < (range._1 + range._2).U)
    ))
    val outSelVec = VecInit(PriorityEncoderOH(outMatchVec))
    val outSelRespVec  = RegEnable(outSelVec, VecInit(Seq.fill(outSelVec.length)(false.B)), io.in.req.fire)
    val reqInvalidAddr = io.in.req.valid && !outSelVec.asUInt.orR

    assert(!reqInvalidAddr, "address decode error, bad addr = 0x%x\n",addr)

    switch(st) {
        is(s_idle) {
            st_en := io.in.req.fire
            st_n  := Mux(reqInvalidAddr, s_error, s_resp) 
        }
        is(s_resp) {
            st_en := io.in.rsp.fire
            st_n  := Mux(io.in.req.fire, 
                     Mux(reqInvalidAddr,s_error, s_resp),
                     s_idle )
        }
        is(s_error) {
            st_en := io.in.rsp.fire
            st_n  := Mux(io.in.req.fire,
                     Mux(reqInvalidAddr, s_error, s_resp),
                     s_idle )
        }
    }

    val st_is_idle  = (st === s_idle)
    val st_is_resp  = (st === s_resp)
    val st_is_error = (st === s_error)
    val no_out_stand    = st_is_idle | (st_is_resp | st_is_error) & io.in.rsp.fire

    
    io.in.req.ready     := Mux1H(outSelVec, io.out.map(_.req.ready)) || st_is_error
    for(i <- 0 until io.out.length) {
        io.out(i).req.valid := outSelVec(i) && io.in.req.valid && no_out_stand
        io.out(i).req.bits  := io.in.req.bits
    }
   
    for(i <-0 until io.out.length) {
        io.out(i).rsp.ready := outSelRespVec(i) && io.in.rsp.ready && st_is_resp
    }
    io.in.rsp.valid := Mux1H(outSelRespVec, io.out.map(_.rsp.valid)) || st_is_error
    io.in.rsp.bits  := Mux1H(outSelRespVec, io.out.map(_.rsp.bits))

}


class SimpleBusCrossbarNto1(n: Int) extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Vec(n, new SimpleBus(32)))
        val out = new SimpleBus(32)
    })

    def fixedPriorityArbiterOH(requests: Seq[Bool]): Seq[Bool] = {
        val higherPriorityMask = requests.scanLeft(false.B)(_ || _)
        val grant              = higherPriorityMask.dropRight(1).map(!_)
        grant
    }

        
    val s_idle :: s_resp :: Nil = Enum(2)
    val st_n    = WireInit(s_idle)
    val st_en   = WireInit(false.B)
    val st      = RegEnable(st_n, s_idle, st_en)

    val InputVldVec = VecInit(io.in.map(_.req.valid))
    val InputGntVec = VecInit(fixedPriorityArbiterOH(InputVldVec))
    val InputSelVec = VecInit(InputVldVec.zip(InputGntVec).map { case (vld, gnt) => vld & gnt })
    val InputSelRespVec = RegEnable(InputSelVec, VecInit(Seq.fill(InputSelVec.length)(false.B)), io.out.req.fire)
    val InputVld    = WireInit(InputVldVec)

    switch(st) {
        is(s_idle) {
            st_en := io.out.req.fire
            st_n  := s_resp
        }
        is(s_resp) {
            st_en := io.out.rsp.fire
            st_n  := Mux(io.out.req.fire, s_resp, s_idle)
        }
    }

    val st_is_idle      = (st === s_idle)
    val st_is_resp      = (st === s_resp)
    val no_out_stand    = st_is_idle | st_is_resp & io.out.rsp.fire

    
    io.out.req.valid    := InputVld.asUInt.orR & no_out_stand
    io.out.req.bits     := Mux1H(InputSelVec, io.in.map(_.req.bits))
    
    for(i <- 0 until io.in.length) {
        io.in(i).req.ready := InputGntVec(i) & io.out.req.ready & no_out_stand
    }

    for(i <- 0 until io.in.length) {
        io.in(i).rsp.valid := InputSelRespVec(i) & io.out.rsp.valid
        io.in(i).rsp.bits  := io.out.rsp.bits
    }

    io.out.rsp.ready  := Mux1H(InputSelRespVec, io.in.map(_.rsp.ready))

}


class CacheBusCrossbarNto1(n : Int) extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Vec(n, new CacheBus))
        val out = new CacheBus
    })

    def fixedPriorityArbiterOH(requests: Seq[Bool]): Seq[Bool] = {
        val higherPriorityMask = requests.scanLeft(false.B)(_ || _)
        val grant              = higherPriorityMask.dropRight(1).map(!_)
        grant
    }

    val s_idle :: s_locked :: s_rsp :: Nil = Enum(3)
    val st_n  = WireInit(s_idle)
    val st_en = WireInit(false.B)
    val st    = RegEnable(st_n, s_idle, st_en)

    val st_is_idle  = (st === s_idle)
    val st_is_locked = (st === s_locked)
    val st_is_rsp   = (st === s_rsp)

    val InputVldVec = VecInit(io.in.map(_.req.valid))
    val InputGntVec = VecInit(fixedPriorityArbiterOH(InputVldVec))
    val InputSelVec = VecInit(InputVldVec.zip(InputGntVec).map { case (vld, gnt) => vld & gnt })
    val InputSelReg = RegEnable(InputSelVec, VecInit(Seq.fill(InputSelVec.length)(false.B)), io.out.req.fire & st_is_idle)
    val last_wr     = Mux1H(InputSelVec, io.in.map(_.req.bits.last))
    val last_wr_locked = Mux1H(InputSelReg, io.in.map(_.req.bits.last))
    val last_rd     = io.out.rsp.bits.last

    switch(st) {
        is(s_idle) {
            st_en := io.out.req.fire
            st_n  := Mux(last_wr, s_rsp, s_locked)
        }
        is(s_locked) {
            st_en := io.out.req.fire
            st_n  := Mux(last_wr_locked, s_rsp, s_locked)
        }
        is(s_rsp) {
            st_en := io.out.rsp.fire
            st_n  := Mux(last_rd, s_idle, s_rsp)
        }
    }


    io.out.req.valid := st_is_idle & InputVldVec.asUInt.orR | st_is_locked & Mux1H(InputSelReg, io.in.map(_.req.valid))

    when (st_is_idle) {
        io.out.req.bits := Mux1H(InputSelVec, io.in.map(_.req.bits))
    } .otherwise {
        io.out.req.bits := Mux1H(InputSelReg, io.in.map(_.req.bits))
    } 

    for(i <- 0 until io.in.length) {
        io.in(i).req.ready := InputGntVec(i) & io.out.req.ready & st_is_idle |
                              InputSelReg(i) & io.out.req.ready & st_is_locked
    }

    for(i <- 0 until io.in.length) {
        io.in(i).rsp.valid := InputSelReg(i) & io.out.rsp.valid
        io.in(i).rsp.bits  := io.out.rsp.bits
    }

    io.out.rsp.ready := Mux1H(InputSelReg, io.in.map(_.rsp.ready))
}


class crossbarCache() extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Vec(2, new CacheBus()))
        val out = new AXI4(32, 32, 2)
    })

    val arbiter     = Module(new CacheBusCrossbarNto1(2))
    val bridge      = Module(new CacheBus2Axi4())
    val buscut  = Module(new CacheBusCut())
    arbiter.io.in <> io.in
    arbiter.io.out <> buscut.io.in
    bridge.io.in  <> buscut.io.out
    io.out <> bridge.io.out

}


class crossbar(ifuDis: List[(Long, Long)], lsuDis: List[(Long, Long)]) extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Vec(2, new SimpleBus(32)))
        val out = Vec(3, new SimpleBus(32))
    })

    val ifuXbar = Module(new SimpleBusCrossbar1toN(ifuDis))
    val lsuXbar = Module(new SimpleBusCrossbar1toN(lsuDis))

    ifuXbar.io.in <> io.in(1)
    lsuXbar.io.in <> io.in(0)

    val ifu2ext     = ifuXbar.io.out(0)
    val ifu2itcm    = ifuXbar.io.out(1)

    val lsu2ext     = lsuXbar.io.out(0)
    val lsu2itcm    = lsuXbar.io.out(1)
    val lsu2dtcm    = lsuXbar.io.out(2)

    val extArbiter  = Module(new SimpleBusCrossbarNto1(2))
    val itcmArbiter = Module(new SimpleBusCrossbarNto1(2))

    extArbiter.io.in(0) <> lsu2ext
    extArbiter.io.in(1) <> ifu2ext
    val arbt2ext = extArbiter.io.out

    itcmArbiter.io.in(0) <> lsu2itcm
    itcmArbiter.io.in(1) <> ifu2itcm
    val arbt2itcm = itcmArbiter.io.out

    io.out(0) <> arbt2itcm
    io.out(1) <> lsu2dtcm
    io.out(2) <> arbt2ext
}