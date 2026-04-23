package cl1

import chisel3._
import chisel3.util._
import utils._
import chisel3.util.HasBlackBoxInline

class dxReq  extends Bundle {
    val invalid = Output(Bool())
    val clean   = Output(Bool())
}

class CoreBus extends Bundle {
    val req = Decoupled(new Bundle {
        val addr    = Output(UInt(32.W))
        val data    = Output(UInt(32.W))
        val wen     = Output(Bool())
        val mask    = Output(UInt(4.W))
        val cache   = Output(Bool())
        val size    = Output(UInt(2.W))
    })
    val rsp = Flipped(Decoupled(new Bundle{
        val data    = Input(UInt(32.W))
        val err     = Input(Bool())
    }))

}

class CacheBus extends Bundle {
    val req = Decoupled(new Bundle {
        val addr    = Output(UInt(32.W))
        val data    = Output(UInt(32.W))
        val wen     = Output(Bool())
        val burst   = Output(Bool())
        val mask    = Output(UInt(4.W))
        val len     = Output(UInt(4.W))
        val size    = Output(UInt(2.W))
        val last   = Output(Bool())
    })
    val rsp = Flipped(Decoupled(new Bundle {
        val data = Input(UInt(32.W))
        val last = Input(Bool())
        val err  = Input(Bool())
    }))
}

class Cl1ICACHE extends Module {
    val io = IO(new Bundle {
        val in      = Flipped(new CoreBus)
        val dxReq   = Flipped(Decoupled(new dxReq))
        val out     = new CacheBus
        val icache_idle = Output(Bool())
    })

    object CacheParams {
        val WAYS  = 2
        val BANKS = 4
        val AW    = 32
        val DW    = 32
        val ROWW  = log2Ceil(DW/8 * BANKS)
        val IDXW  = 7
        val TAGW  = AW - IDXW - ROWW
    }

    def fixedPriorityArbiterOH(requests: Seq[Bool]): (Bool, UInt) = {
        val higherPriorityMask = requests.scanLeft(false.B)(_ || _)
        val grant              = higherPriorityMask.dropRight(1).map(!_)
        val sel                = Cat(requests.zip(grant).map { case (req, g) => req && g }.reverse)
        val allzero            = ~Cat(requests).orR
        (allzero, sel)
    }

    def randomchoose(Ways: Int = 2): UInt  = {
        val chooseWay = if(Ways > 1) ( 1.U << LFSR8()(log2Up(Ways)-1,0)) else "b1".U
        chooseWay
    }

    def way2lruchoose(Ways: Int = 2):UInt = {
        val dataphase = RegNext(io.in.req.fire, false.B)
        val access_way = Fill(Ways,dataphase) & Mux(ic_hit, Cat(ic_hit_seq.reverse) , replace_way)
        val age_idx    = Fill(CacheParams.IDXW, dataphase) & ic_idx_r
        val ageTab    = RegInit(VecInit(Seq.fill(1 << CacheParams.IDXW)(false.B)))
        when(dataphase) {
            ageTab(age_idx) := access_way >> 1
            // printf("ageTab(%d) <= %b\n", age_idx, access_way >> 1)
        }
        val chooseWay = 1.U << ~ageTab(age_idx)
        chooseWay
    }

    val core_req_hsked = io.in.req.fire
    val core_rsp_hsked = io.in.rsp.fire
    val core_req_addr  = io.in.req.bits.addr

    val req_inval      = io.dxReq.valid & io.dxReq.bits.invalid
    
    val  s_inval :: s_idle :: s_lookup :: s_replace :: s_refill :: Nil = Enum(5)
    val ic_st_n = WireInit(s_inval)
    val ic_st   = RegNext(ic_st_n, s_inval)

    val s_is_idle       = ic_st === s_idle
    val s_is_lookup     = ic_st === s_lookup
    val s_is_replace    = ic_st === s_replace
    val s_is_refill     = ic_st === s_refill
    val s_is_inval      = ic_st === s_inval

    val ic_hit          = Wire(Bool())
    val rd_last_trans   = Wire(Bool())

    val inval_done      = Wire(Bool())
    
    switch(ic_st) {
        is(s_idle) {
            ic_st_n  := Mux(req_inval, s_inval, Mux(core_req_hsked, s_lookup, s_idle))
        }
        is(s_lookup) {
            ic_st_n  := Mux(!ic_hit, s_replace, Mux(core_rsp_hsked, Mux(core_req_hsked,s_lookup, s_idle), s_lookup))
        }
        is(s_replace) {
            ic_st_n  := Mux(io.out.req.ready, s_refill, s_replace)
        }
        is(s_refill) {
            ic_st_n  := Mux(rd_last_trans, s_idle, s_refill)
        }
        is(s_inval) {
            ic_st_n := Mux(inval_done, s_idle, s_inval)
        }
    }

    val data_srams = Seq.tabulate(CacheParams.WAYS, CacheParams.BANKS) (
        (w, b) => Module(new sram(1 << CacheParams.IDXW, CacheParams.DW,  false)).suggestName(s"data_sram_w${w}_b${b}")
    )

    val tagv_srams = Seq.tabulate(CacheParams.WAYS) (
        w => Module(new sram(1 << CacheParams.IDXW, CacheParams.TAGW + 1, false)).suggestName(s"tagv_sram_w${w}")
    )

    val cacheable_reg = Wire(Bool())
    val tagv_read  = (s_is_idle || s_is_lookup) && core_req_hsked
    val tagv_write = s_is_refill & rd_last_trans & cacheable_reg

    val req_addr_reg  = RegEnable(core_req_addr, 0.U, core_req_hsked)
    val req_size_reg  = RegEnable(io.in.req.bits.size, 0.U, core_req_hsked)
    cacheable_reg     := RegEnable(io.in.req.bits.cache, false.B, core_req_hsked)

    val ic_addr      = core_req_addr
    val ic_addr_r    = req_addr_reg
    val ic_ofst_r    = ic_addr_r(CacheParams.ROWW-1, 2)
    val ic_idx_r     = ic_addr_r(CacheParams.IDXW + CacheParams.ROWW - 1, CacheParams.ROWW)
    val ic_idx       = ic_addr(CacheParams.IDXW + CacheParams.ROWW - 1, CacheParams.ROWW)
    val ic_tag_r     = ic_addr_r(CacheParams.AW-1, CacheParams.AW - CacheParams.TAGW)  
    val ic_hit_seq   = tagv_srams.map(sram => sram.io.dout(CacheParams.TAGW) && 
                       sram.io.dout(CacheParams.TAGW-1, 0) === ic_tag_r)
    val srams_idx   = Mux(core_req_hsked, ic_idx, ic_idx_r)

    ic_hit          := ic_hit_seq.reduce(_ || _) && cacheable_reg
    val ic_data_seq = data_srams.map{way => VecInit(way.map(_.io.dout))(ic_ofst_r)}
    val ic_hit_data  = Mux1H(ic_hit_seq, ic_data_seq)

    val tagInVldSeq  = tagv_srams.map(!_.io.dout(CacheParams.TAGW))
    val (tagv_allvld, tagv_invld) = fixedPriorityArbiterOH(tagInVldSeq)
    val replace_way      = Wire(UInt(CacheParams.WAYS.W))
    replace_way         := Mux(tagv_allvld, randomchoose(CacheParams.WAYS), tagv_invld)
    // replace_way         := Mux(tagv_allvld, way2lruchoose(CacheParams.WAYS), tagv_invld)
    val replace_way_r = RegEnable(replace_way,0.U, s_is_lookup)

    val (inval_idx, inval_cnt_end) = Counter(s_is_inval, 1 << CacheParams.IDXW)
    inval_done := inval_cnt_end
    val tagv_inval = s_is_inval

    for(i <- 0 until CacheParams.WAYS) {
        tagv_srams(i).io.ena  := tagv_read || tagv_write & replace_way_r(i) || tagv_inval 
        tagv_srams(i).io.addr := Mux(tagv_inval, inval_idx, srams_idx)
        tagv_srams(i).io.wea  := tagv_write && replace_way_r(i) || tagv_inval
        tagv_srams(i).io.din  := Mux(tagv_inval, 0.U, Cat(true.B,ic_tag_r))
    }

    val icache_read  = tagv_read 
    val icache_write = s_is_refill & cacheable_reg & io.out.rsp.valid

    val burst_trans  = cacheable_reg
    val single_trans = ~burst_trans
    val cnt_en = s_is_refill & burst_trans & io.out.rsp.valid
    val (replace_bank_cnt, bank_cnt_wrap) = Counter(cnt_en, CacheParams.BANKS)

    for(i <- 0 until CacheParams.WAYS) {
        for(j <- 0 until CacheParams.BANKS) {
            data_srams(i)(j).io.ena  := icache_read || icache_write && replace_way_r(i)
            data_srams(i)(j).io.addr := srams_idx
            data_srams(i)(j).io.wea  := icache_write && replace_way_r(i) && (replace_bank_cnt === j.U)
            data_srams(i)(j).io.din  := io.in.rsp.bits.data
        }
    }

    rd_last_trans := io.out.rsp.fire & io.out.rsp.bits.last

    // rsp to core
    // val req_outs_set  = core_req_hsekd
    // val req_outs_clr  = core_rsp_hsked
    // val req_outs_en   = req_outs_set | req_outs_clr
    // val req_outs_n    = req_outs_set | ~req_outs_clr
    // val core_req_outs = RegEnable(req_outs_n, false.B, req_outs_en)
    // val no_outs = core_req_outs || req_outs_clr
    // val dx_req_flag     = Wire(Bool())
    // val dx_req_flag_set = req_inval & ~dx_req_flag
    // val dx_req_flag_clr = dx_req_flag 
    io.dxReq.ready := inval_done & req_inval

    io.in.req.ready   :=  (s_is_idle  & ~req_inval || s_is_lookup)
    io.in.rsp.valid   :=  s_is_lookup & ic_hit || s_is_refill && io.out.rsp.fire && ((replace_bank_cnt === ic_ofst_r) & burst_trans | single_trans)
    io.in.rsp.bits.data := Mux1H(Seq(
        s_is_lookup -> ic_hit_data,
        s_is_refill -> io.out.rsp.bits.data
    ))
    io.in.rsp.bits.err   := false.B

    // req to bus
    val burst_start_addr   =  Cat(req_addr_reg(CacheParams.AW-1, CacheParams.ROWW),Fill(CacheParams.ROWW, false.B))
    val single_addr        = req_addr_reg
    io.out.req.valid        := s_is_replace
    io.out.req.bits.addr    := Mux(burst_trans, burst_start_addr, single_addr)
    io.out.req.bits.data    := 0.U
    io.out.req.bits.wen     := false.B
    io.out.req.bits.burst   := burst_trans
    io.out.req.bits.mask    := Fill(CacheParams.DW/8, true.B)
    io.out.req.bits.len     := Mux(burst_trans,(CacheParams.BANKS - 1).U, 0.U)
    io.out.req.bits.size    := Mux(burst_trans,"b10".U, req_size_reg)
    io.out.req.bits.last    := s_is_replace

    io.out.rsp.ready        := true.B

    io.icache_idle          := s_is_idle

    val ic_statistics = false

    if(ic_statistics) {
        val fetch_cnt = RegInit(0.U(32.W))
        val hit_cnt   = RegInit(0.U(32.W))
        fetch_cnt := Mux(core_req_hsked, fetch_cnt + 1.U, fetch_cnt)
        hit_cnt   := Mux(ic_hit & s_is_lookup, hit_cnt + 1.U, hit_cnt)
        
        class Stat extends BlackBox with HasBlackBoxInline {
            val io = IO(new Bundle {
                val all_cnt = Input(UInt(32.W))
                val hit_cnt = Input(UInt(32.W))
            })
            setInline("Stat.v",
            """module Stat(
              | input [31:0] all_cnt,
              | input [31:0] hit_cnt
              |);
              |final begin
              |  $display("--- ICACHE STATISTICS ---");
              |  $display("Total Fetches: %d", all_cnt);
              |  $display("Cache Hits:    %d", hit_cnt);
              |  if (all_cnt > 0) begin
              |    $display("Hit Rate:     %d %%", (hit_cnt * 100) / all_cnt);
              |  end
              |end
              |endmodule
              |""".stripMargin)
        }

        val stat = Module(new Stat)
        stat.io.all_cnt := fetch_cnt
        stat.io.hit_cnt := hit_cnt
    }

}