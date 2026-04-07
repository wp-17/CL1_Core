package cl1
import chisel3._
import chisel3.util._

class IFU2BUSignal extends Bundle {
    val req_seq  = Output(Bool())
    val req_pc   = Output(UInt(32.W))
    val pc_reg   = Output(UInt(32.W))
}

class BUS2IFUSignal extends Bundle {
   val inst      = Output(UInt(32.W)) 
   val err       = Output(Bool())
}

class FetchAlign extends Module {
    val io = IO(new Bundle {
        val fromifu = Flipped(Decoupled(new IFU2BUSignal()))
        val toifu   = Decoupled(new BUS2IFUSignal())
        val bus     = new CoreBus()
    })

    val memReq = Wire(chiselTypeOf(io.bus.req))
    // val memReq  = io.bus.req
    val memResp = io.bus.rsp
    
    val ifu_req_vld   = io.fromifu.valid
    val ifu_req_rdy   = Wire(Bool())
    val ifu_req_hsked = ifu_req_vld & ifu_req_rdy

    val ifu_rsp_vld   = Wire(Bool())
    val ifu_rsp_rdy   = io.toifu.ready
    val ifu_rsp_hsked = ifu_rsp_vld & ifu_rsp_rdy

    val ifu_req_seq   = io.fromifu.bits.req_seq
    val ifu_req_pc    = io.fromifu.bits.req_pc
    val pc_r          = io.fromifu.bits.pc_reg

    val bus_req_vld   = Wire(Bool())
    val bus_req_rdy   = memReq.ready
    val bus_req_hsked = bus_req_vld & bus_req_rdy

    val bus_rsp_vld   = memResp.valid 
    val bus_rsp_rdy   = Wire(Bool())
    val bus_rsp_hsked = bus_rsp_vld & bus_rsp_rdy
    val bus_rsp_dat   = memResp.bits.data

    val pc_unalign32    = pc_r(1) === true.B
    val pc_align32      = ~pc_unalign32

    val req_pc_unalign32 = ifu_req_pc(1)
    val req_pc_align32   = ~req_pc_unalign32

    val idle :: fetch_1st :: fetch_2st_wait :: fetch_2st :: nil = Enum(4)

    val fetch_st_n         = WireInit(idle)
    val fetch_st_en        = WireInit(false.B)
    val fetch_st           = RegEnable(fetch_st_n, idle, fetch_st_en)

    val st_idle             = (fetch_st === idle)
    val st_fetch_1st        = (fetch_st === fetch_1st)
    val st_fetch_2st_wait   = (fetch_st === fetch_2st_wait)
    val st_fetch_2st        = (fetch_st === fetch_2st)

    val rspl_is_16i         = bus_rsp_dat(1,0) =/= "b11".U
    val rsph_is_32i         = bus_rsp_dat(17,16) === "b11".U

    val ir_buf_vld_set      = (pc_align32 & rspl_is_16i | pc_unalign32) & rsph_is_32i
    val ir_buf_vld_clr      = ~ifu_req_seq
    val ir_buf_vld_n        = ir_buf_vld_set & ~ir_buf_vld_clr & bus_rsp_hsked
    val ir_buf_vld          = RegEnable(ir_buf_vld_n, false.B, bus_rsp_hsked)

    val ir_buf_n            = bus_rsp_dat(31,16)
    val ir_buf_en           = ir_buf_vld_n
    val ir_buf              = RegEnable(ir_buf_n, 0.U, ir_buf_en)

    val ifu_req_seq_r       = RegEnable(ifu_req_seq, false.B, ifu_req_hsked)
    val need_2st_fetch      = bus_rsp_vld & pc_unalign32 & rsph_is_32i & ~ifu_req_seq_r

    switch(fetch_st) {
        is(idle) {
            fetch_st_en := ifu_req_hsked
            fetch_st_n  := fetch_1st
        }
        is(fetch_1st) {
            fetch_st_en := bus_rsp_hsked
            fetch_st_n  := Mux1H(Seq(
                (need_2st_fetch & bus_req_rdy)  -> fetch_2st,
                (need_2st_fetch & ~bus_req_rdy) -> fetch_2st_wait,
                (~need_2st_fetch & ifu_req_hsked)  -> fetch_1st,
                (~need_2st_fetch & ~ifu_req_hsked) -> idle 
            ))
        }
        is(fetch_2st_wait) {
            fetch_st_en := bus_req_hsked
            fetch_st_n  := fetch_2st
        }
        is(fetch_2st) {
            fetch_st_en := ifu_rsp_hsked
            fetch_st_n  := Mux1H(Seq(
                ifu_req_hsked              -> fetch_1st,
                ~ifu_req_hsked             -> idle
            ))
        }
    }

    val last_rsp_buf_vld      = Mux(bus_rsp_vld, ir_buf_vld_n, ir_buf_vld)
    val f1st_req_nxtline      = req_pc_unalign32 & last_rsp_buf_vld & ifu_req_seq
    val f2st_req_vld = st_fetch_1st & need_2st_fetch | st_fetch_2st_wait
    bus_req_vld      := ifu_req_vld | f2st_req_vld 

    val pc_adder_op1 = Mux(f2st_req_vld, pc_r, ifu_req_pc)
    val pc_adder_op2 = Mux(f1st_req_nxtline | f2st_req_vld, 2.U, 0.U)
    val pc_adder_res = pc_adder_op1 + pc_adder_op2
    val bus_fetch_addr = Cat(pc_adder_res(31,2),0.U(2.W))

    val ifu_rsp_vld_mask = st_fetch_1st & need_2st_fetch
    ifu_rsp_vld          := bus_rsp_vld & ~ifu_rsp_vld_mask

    val req_nline_r      = RegEnable(f1st_req_nxtline, false.B,ifu_req_hsked)
    val inst_cross       = pc_unalign32 & (req_nline_r | st_fetch_2st)
    val inst_hi          = pc_unalign32 & ~inst_cross
    val inst_lo_or32     = pc_align32

    val rsp_inst = Mux1H(Seq(
        inst_cross      -> Cat(bus_rsp_dat(15,0), ir_buf),
        inst_hi         -> bus_rsp_dat(31,16),
        inst_lo_or32    -> bus_rsp_dat
    ))

    ifu_req_rdy         := bus_req_rdy
    bus_rsp_rdy         := ifu_rsp_rdy | ifu_rsp_vld_mask

    io.fromifu.ready    := ifu_req_rdy
    io.toifu.valid      := ifu_rsp_vld
    io.toifu.bits.inst  := rsp_inst
    io.toifu.bits.err   := 0.U

    memReq.valid             := bus_req_vld
    memReq.bits.addr         := bus_fetch_addr
    memReq.bits.wen          := false.B
    memReq.bits.size         := 2.U
    memReq.bits.data         := 0.U
    memReq.bits.mask         := 0.U
    // memReq.bits.cache        := (bus_fetch_addr(31) === 1.U)
    val i_cached                 = if(globalConfig.simpleSocTest) SimpleSocMemoryMap.isICacheable(bus_fetch_addr) else  MemoryMap.isICacheable(bus_fetch_addr)
    memReq.bits.cache        := i_cached

    memResp.ready            := bus_rsp_rdy

    // io.bus.req  <>           BypReg(memReq)
    io.bus.req      <>       memReq

}