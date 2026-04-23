package cl1

import chisel3._
import chisel3.util._
import utils._
import chisel3.util.HasBlackBoxInline

class rf_ram(val WordDepth:Int = 256, val DW:Int = 1) extends Module {
    val io   = IO(new Bundle {
        val waddr   = Input(UInt(log2Ceil(WordDepth).W))
        val din     = Input(UInt(DW.W))
        val wea     = Input(Bool())
        val clean   = Input(Bool())
        val raddr   = Input(UInt(log2Ceil(WordDepth).W))
        val dout    = Output(UInt(DW.W))
    })

    val ram = RegInit(VecInit(Seq.fill(WordDepth)(0.U(DW.W))))

    when(io.wea) {
        ram(io.waddr) := io.din
    }
    
    when(io.clean) {
        ram.foreach(_ := 0.U)
    }

    io.dout := ram(io.raddr)
}

class Cl1DCACHE extends Module {
    val io = IO(new Bundle {
        val in      = Flipped(new CoreBus)
        val dxReq   = Flipped(Decoupled(new dxReq))
        val out     = new CacheBus
        val dcache_idle = Output(Bool())
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
        val access_way = Fill(Ways,dataphase) & Mux(dc_hit, Cat(dc_hit_seq.reverse) , replace_way)
        val age_idx    = Fill(CacheParams.IDXW, dataphase) & dc_idx_r
        val ageTab    = RegInit(VecInit(Seq.fill(1 << CacheParams.IDXW)(false.B)))
        when(dataphase) {
            ageTab(age_idx) := access_way >> 1
            // printf("ageTab(%d) <= %b\n", age_idx, access_way >> 1)
        }
        val chooseWay = 1.U << ~ageTab(age_idx)
        chooseWay
    }

    val s_inval :: s_idle :: s_lookup :: s_miss :: s_waitwrsp :: s_replace :: s_refill :: s_clean :: Nil = Enum(8)
    val dc_st_n = WireInit(s_inval)
    val dc_st   = RegNext(dc_st_n, s_inval)

    val s_is_inval      = dc_st === s_inval
    val s_is_idle       = dc_st === s_idle
    val s_is_lookup     = dc_st === s_lookup
    val s_is_miss       = dc_st === s_miss
    val s_is_waitwrsp   = dc_st === s_waitwrsp
    val s_is_replace    = dc_st === s_replace
    val s_is_refill     = dc_st === s_refill
    val s_is_clean      = dc_st === s_clean

    val dc_hit          = Wire(Bool())
    val wr_last_trans   = Wire(Bool())
    val rd_last_trans   = Wire(Bool())

    val dc_write_r        = RegEnable(io.in.req.bits.wen, false.B, io.in.req.fire)
    val dc_read_r         = ~dc_write_r

    val replace_way_dirty = Wire(Bool())
    val dcacheable      = RegEnable(io.in.req.bits.cache, false.B, io.in.req.fire)
    val cachemiss_needwb = ~dc_hit & replace_way_dirty & dcacheable
    val cachemiss_noneedwb = ~dc_hit & ~replace_way_dirty & dcacheable
    val uncache_wr      = dc_write_r && !dcacheable
    val uncache_rd      = dc_read_r && !dcacheable

    val inval_done      = Wire(Bool())
    val clean_done      = Wire(Bool())
    val req_inval       = io.dxReq.valid && io.dxReq.bits.invalid
    val req_clean       = io.dxReq.valid && io.dxReq.bits.clean

    switch(dc_st) {
        is(s_inval) {
            dc_st_n := Mux(inval_done, s_idle, s_inval)
        }
        is(s_idle) {
            dc_st_n := Mux((req_inval | req_clean), s_clean, Mux(io.in.req.fire,s_lookup, s_idle))
        }
        is(s_lookup) {
            val dc_st_n_pre = Mux1H(Seq(
                (io.in.rsp.valid && io.in.req.fire)                 -> s_lookup,
                (io.in.rsp.valid && ~io.in.req.fire)                -> s_idle,
                (cachemiss_needwb       | uncache_wr)               -> s_miss,
                (cachemiss_noneedwb     | uncache_rd)               -> s_replace
            ))

            dc_st_n := Mux(req_inval, s_inval, Mux(req_clean, s_clean, dc_st_n_pre))
        }
        is(s_miss) {
            dc_st_n := Mux(wr_last_trans, s_waitwrsp, s_miss)
        }
        is(s_waitwrsp) {
            dc_st_n := Mux(io.out.rsp.fire, Mux(dcacheable, s_replace, s_idle), s_waitwrsp)
        }
        is(s_replace){
            dc_st_n := Mux(io.out.req.ready, s_refill, s_replace)
        }
        is(s_refill) {
            dc_st_n := Mux(rd_last_trans, s_idle, s_refill)
        }
        is(s_clean) {
            dc_st_n := MuxCase(s_clean, Seq(
                (clean_done & req_inval) -> s_inval, 
                (clean_done & req_clean) -> s_idle))
        }
    }

    val s_clean_idle :: s_find_dirtyline :: s_wr_dirtyline :: s_wait_wrsp :: s_clean_end :: nil = Enum(6)
    val clean_st_n      = WireInit(s_clean_idle)
    val clean_st        = RegNext(clean_st_n, s_clean_idle)

    val s_is_clean_idle     = (clean_st === s_clean_idle)
    val s_is_find_dirtyline = (clean_st === s_find_dirtyline)
    val s_is_wr_dirtyline   = (clean_st === s_wr_dirtyline)
    val s_is_wait_wrsp      = (clean_st === s_wait_wrsp)
    val s_is_clean_end      = (clean_st === s_clean_end)

    val cache_clean = s_is_clean
    val cleanline_dirty     = Wire(Bool())
    val line_cnt            = Wire(UInt())
    val last_line           = Wire(Bool())


    val way_inc_find_dirtyline = last_line & ~cleanline_dirty & s_is_find_dirtyline
    val way_inc_wait_wrsp      = last_line & io.out.rsp.fire & s_is_wait_wrsp

    val way_cnt_en = s_is_clean & (way_inc_find_dirtyline | way_inc_wait_wrsp)
    val (way_cnt, way_cnt_end) = Counter(way_cnt_en, CacheParams.WAYS)

    val line_inc_find_dirtyline = s_is_find_dirtyline & ~cleanline_dirty
    val line_inc_wait_wrsp       = io.out.rsp.fire & s_is_wait_wrsp
    val clean_line_cnt_en   = s_is_clean & (line_inc_find_dirtyline | line_inc_wait_wrsp)
    val last_way            = way_cnt_end

    clean_done              := s_is_clean_end

    dontTouch(way_cnt_en)
    dontTouch(way_inc_find_dirtyline)
    dontTouch(way_inc_wait_wrsp)
    dontTouch(last_line)

    switch(clean_st) {
        is(s_clean_idle) {
            clean_st_n := Mux(cache_clean, s_find_dirtyline, s_clean_idle)
                                
        }
        is(s_find_dirtyline) {
            clean_st_n := MuxCase(s_find_dirtyline, Seq(
                cleanline_dirty                             -> s_wr_dirtyline,
                (last_line & last_way & ~cleanline_dirty)   -> s_clean_end
                ))
        }
        is(s_wr_dirtyline) {
            clean_st_n := Mux(wr_last_trans, s_wait_wrsp, s_wr_dirtyline)
        }
        is(s_wait_wrsp) {
            clean_st_n := MuxCase(s_wait_wrsp,Seq(
                (last_line & last_way & io.out.rsp.fire)  -> s_clean_end,
                (~last_way & io.out.rsp.fire) -> s_find_dirtyline
            ))
        }
        is(s_clean_end) {
            clean_st_n := s_clean_idle
        }

    }

    val s_wb_idle :: s_wb_write :: Nil = Enum(2)
    
    val wb_st_n = WireInit(s_wb_idle)
    val wb_st   = RegNext(wb_st_n, s_wb_idle)

    val wb_is_idle  = (wb_st === s_wb_idle)
    val wb_is_write = (wb_st === s_wb_write)

    val wb_condi = s_is_lookup && dc_hit && dc_write_r

    switch(wb_st) {
        is(s_wb_idle) {
            wb_st_n := Mux(wb_condi, s_wb_write, s_wb_idle)
        }
        is(s_wb_write) {
            wb_st_n := Mux(wb_condi, s_wb_write, s_wb_idle)
        }
    }

    val data_srams = Seq.tabulate(CacheParams.WAYS, CacheParams.BANKS) (
        (w, b) => Module(new sram(1 << CacheParams.IDXW, CacheParams.DW,  true)).suggestName(s"data_sram_w${w}_b${b}")
    )

    val tagv_srams = Seq.tabulate(CacheParams.WAYS) (
        w => Module(new sram(1 << CacheParams.IDXW, CacheParams.TAGW + 1, false)).suggestName(s"tagv_sram_w${w}")
    )

    val dirty_rf   = Seq.tabulate(CacheParams.WAYS) (
        w => Module(new rf_ram(1 << CacheParams.IDXW, 1)).suggestName(s"dirty_rf_w${w}")
    )

    val req_addr_reg  = RegEnable(io.in.req.bits.addr, 0.U, io.in.req.fire)
    val req_size_reg  = RegEnable(io.in.req.bits.size, 0.U, io.in.req.fire)

    val read_req      = ! io.in.req.bits.wen
    val dc_ofst       = io.in.req.bits.addr(CacheParams.ROWW-1, 2)
    val dc_ofst_r     = req_addr_reg(CacheParams.ROWW-1, 2)
    val wb_ofst_r     = RegEnable(dc_ofst_r, 0.U, wb_condi)
    val dc_idx        = io.in.req.bits.addr(CacheParams.IDXW + CacheParams.ROWW - 1, CacheParams.ROWW)
    val dc_idx_r      = req_addr_reg(CacheParams.IDXW + CacheParams.ROWW - 1, CacheParams.ROWW)
    val dc_tag_r      = req_addr_reg(CacheParams.AW-1, CacheParams.AW - CacheParams.TAGW)

    val cycl0_write   = io.in.req.bits.wen
    val cycl0_read    = !cycl0_write
    val cycl1_vld     = RegNext(io.in.req.fire, false.B)
    val cycl1_write   = dc_write_r & cycl1_vld
    val cycl2_write   = wb_is_write

    val rw_conflict  = (cycl1_write && cycl0_read && (io.in.req.bits.addr === req_addr_reg) ||
                        cycl2_write && cycl0_read && (dc_ofst === wb_ofst_r)) &
                        dcacheable

    // need remove (s_is_miss | s_is_lookup & cachemiss_needwb)
    val tagv_read    = (s_is_idle || s_is_lookup) && io.in.req.fire || (s_is_miss | s_is_lookup & cachemiss_needwb)
    val tagv_write   = s_is_refill && rd_last_trans && dcacheable

    val tag_vld_seq      = tagv_srams.map(sram => sram.io.dout(CacheParams.TAGW))
    val tag_compare_seq  = tagv_srams.map(_.io.dout(CacheParams.TAGW-1,0) === dc_tag_r)
    val dc_hit_seq       = tag_vld_seq.zip(tag_compare_seq).map {case (vld, cmp) => vld && cmp}
    val wb_hit_seq_r    = RegEnable(VecInit(dc_hit_seq), VecInit(Seq.fill(CacheParams.WAYS)(false.B)), wb_condi)
    dc_hit               := dc_hit_seq.reduce(_ || _) && dcacheable

    val tagInvldSeq      = tag_vld_seq.map(!_)
    val (wayAllVld, wayInvldOneHot) = fixedPriorityArbiterOH(tagInvldSeq)
    val replace_way      = Wire(UInt(CacheParams.WAYS.W))
    replace_way     := Mux(wayAllVld, randomchoose(CacheParams.WAYS), wayInvldOneHot)
    // replace_way         := Mux(wayAllVld, way2lruchoose(CacheParams.WAYS), wayInvldOneHot)
    val replace_way_r   = RegEnable(replace_way, 0.U, s_is_lookup)

    val idx_cnt_en = s_is_inval | clean_line_cnt_en
    val (inval_idx, inval_cnt_end) = Counter(idx_cnt_en, 1 << CacheParams.IDXW)
    inval_done := inval_cnt_end
    last_line  := (inval_idx === ((1 << CacheParams.IDXW) -1).U)
    line_cnt   := inval_idx
    val tagv_inval = s_is_inval
    
    for (w <- 0 until CacheParams.WAYS) {
        tagv_srams(w).io.ena  := tagv_read | tagv_write & replace_way_r(w) || tagv_inval || cache_clean
        tagv_srams(w).io.addr := Mux(tagv_inval | cache_clean, inval_idx, Mux(io.in.req.fire, dc_idx, dc_idx_r))
        tagv_srams(w).io.wea  := tagv_write & replace_way_r(w) || tagv_inval
        tagv_srams(w).io.din  := Mux(tagv_inval, 0.U, Cat(true.B, dc_tag_r))
    }

    // |w0|w1|w2|
    // |--|r0|r1|
    // |--|--|r0|
    //  r1 cycle read dirty table
    val wb_idx_r        = RegEnable(dc_idx_r, 0.U, wb_condi)
    val dirty_hitwb_set       = wb_is_write 
    val dirty_misswb_set      = s_is_refill & rd_last_trans & dcacheable & dc_write_r
    val dirty_set       = dirty_hitwb_set | dirty_misswb_set
    val dirty_clr       = s_is_refill & rd_last_trans & dcacheable & dc_read_r
    val dirty_n         = dirty_set | ~dirty_clr
    val dirty_way_nxt   = Fill(CacheParams.WAYS,(dc_idx_r === wb_idx_r) & dirty_hitwb_set) & Cat(wb_hit_seq_r.reverse)
    val dirty_way       = Cat(dirty_rf.map(_.io.dout).reverse) | dirty_way_nxt
    replace_way_dirty   := (replace_way & dirty_way).orR & dcacheable
    cleanline_dirty := Cat(dirty_rf.map(_.io.dout).reverse)(way_cnt)

    for (w <- 0 until CacheParams.WAYS) {
        dirty_rf(w).io.waddr    :=  Mux1H(Seq(
                                    dirty_hitwb_set -> wb_idx_r,
                                    (dirty_misswb_set | dirty_clr) -> dc_idx_r
                                ))
        dirty_rf(w).io.din      :=  dirty_n
        dirty_rf(w).io.wea      :=  Mux1H(Seq(
                                    dirty_hitwb_set -> wb_hit_seq_r(w),
                                    (dirty_misswb_set | dirty_clr) -> replace_way_r(w)
                                ))
        dirty_rf(w).io.clean    := s_is_clean_end
        dirty_rf(w).io.raddr    :=  Mux(s_is_clean, line_cnt, dc_idx_r)
    }

    val dcache_read  = tagv_read
    val wb2cache     = wb_is_write
    val bus2cache    = s_is_refill && io.out.rsp.valid && dcacheable

    // can remove ?
    val cache2bus    = s_is_miss | s_is_lookup & cachemiss_needwb

    val cleancache2bus = cache_clean

    val wdat_mask_r     = RegEnable(io.in.req.bits.mask, 0.U, io.in.req.fire & io.in.req.bits.wen)
    val wdat_r          = RegEnable(io.in.req.bits.data, 0.U, io.in.req.fire & io.in.req.bits.wen)
    val wbcache_mask_r       = RegEnable(wdat_mask_r, 0.U, wb_condi)
    val wbcache_data_r       = RegEnable(wdat_r, 0.U, wb_condi)

    val wb_bank   = Seq.tabulate(CacheParams.WAYS, CacheParams.BANKS) (
        (w, b) => wb_hit_seq_r(w) & (wb_ofst_r === b.U) & wb2cache
    )

    val busMixwb_dat = Cat((0 until CacheParams.BANKS).map{ i => Mux(wdat_mask_r(i) & dc_write_r, wdat_r(8*(i+1)-1, 8*i), io.out.rsp.bits.data(8*(i+1)-1, 8*i))}.reverse)

    val burst_trans     = dcacheable
    val single_trans    = ~burst_trans
    val clean_wr        =   cache_clean & s_is_wr_dirtyline
    val bank_cnt_en     =   s_is_miss   & burst_trans & io.out.req.fire |
                            s_is_refill & burst_trans & io.out.rsp.valid |
                            clean_wr & io.out.req.fire
    val (replace_bank_cnt, bank_cnt_wrap) = Counter(bank_cnt_en, CacheParams.BANKS)

    for(i <- 0 until CacheParams.WAYS) {
        for(j <- 0 until CacheParams.BANKS) {
            data_srams(i)(j).io.ena  := dcache_read | wb2cache & wb_hit_seq_r(i) | bus2cache & replace_way_r(i) | cache_clean
            data_srams(i)(j).io.addr := Mux(wb_bank(i)(j), wb_idx_r, 
                                            (Fill(CacheParams.IDXW, bus2cache) & dc_idx_r |
                                            Fill(CacheParams.IDXW, io.in.req.fire) & dc_idx |
                                            Fill(CacheParams.IDXW, cache2bus) & dc_idx_r) |
                                            Fill(CacheParams.IDXW, cleancache2bus) & line_cnt)
            data_srams(i)(j).io.wea  := Fill(4,wb_bank(i)(j)) & wbcache_mask_r |
                                        Fill(4, bus2cache & (replace_way_r(i) && (replace_bank_cnt === j.U)))
            data_srams(i)(j).io.din  := Mux1H(Seq(
                wb2cache    -> wbcache_data_r,
                bus2cache   -> Mux(dc_ofst_r === j.U, busMixwb_dat, io.out.rsp.bits.data)
            ))
        }
    }

    val dc_data_seq = data_srams.map{ way => VecInit(way.map(_.io.dout))(dc_ofst_r)}
    val dc_hit_data = Mux1H(dc_hit_seq, dc_data_seq)
    val bc_one_hot  = (0 until CacheParams.BANKS).map{i => replace_bank_cnt === i.U}
    val cachedata_out_seq = data_srams.map{ way => way.map(_.io.dout).zip(bc_one_hot).map{case (data,sel) => Fill(data.getWidth,sel) & data}.reduce(_ | _)}
    val cachedata_out = Mux1H(replace_way_r, cachedata_out_seq)

    rd_last_trans   := io.out.rsp.fire & io.out.rsp.bits.last
    // wr_last_trans   := (io.out.req.fire & bank_cnt_wrap & dcacheable | io.out.req.fire & ~dcacheable) & ~s_is_clean |
    //                    (io.out.req.fire & bank_cnt_wrap)  & s_is_clean
    wr_last_trans   := Mux1H(Seq(
        ~s_is_clean  -> (io.out.req.fire & bank_cnt_wrap & dcacheable | io.out.req.fire & ~dcacheable),
        s_is_clean   -> (io.out.req.fire & bank_cnt_wrap)
    ))
    io.in.req.ready := (s_is_idle || s_is_lookup) && !rw_conflict && !(req_inval | req_clean)

    io.in.rsp.valid := s_is_lookup & dc_hit |         // cache hit write / read
                       s_is_waitwrsp & io.out.rsp.fire & !dcacheable | // uncache write
                       s_is_refill & io.out.rsp.fire & ((replace_bank_cnt === dc_ofst_r) & burst_trans | single_trans) //cache miss read
    io.in.rsp.bits.data := Mux1H(Seq(
        s_is_lookup -> dc_hit_data,
        s_is_refill -> io.out.rsp.bits.data
    ))
    io.in.rsp.bits.err   := false.B

    val replace_way_tag = Mux1H(replace_way_r, tagv_srams.map(_.io.dout(CacheParams.TAGW-1,0)))
    val wburst_addr = Cat(replace_way_tag, dc_idx_r, Fill(CacheParams.ROWW, false.B))
    val rburst_addr = Cat(req_addr_reg(CacheParams.AW-1, CacheParams.ROWW), Fill(CacheParams.ROWW, false.B))
    val single_addr = req_addr_reg

    val cway_tag        = VecInit(tagv_srams.map(_.io.dout(CacheParams.TAGW-1,0)))(way_cnt)
    val cidx            = line_cnt
    val cwburst_addr    = Cat(cway_tag, cidx, Fill(CacheParams.ROWW, false.B))
    val cleanway_oh     = 1.U << way_cnt
    val cleandata_out   = Mux1H(cleanway_oh, cachedata_out_seq)

    val wbHit_way            = Cat(wb_hit_seq_r.reverse)
    val wait_prewrite        = ~RegNext(wb_is_write & (wb_ofst_r === 0.U) & (replace_way & wbHit_way).orR , false.B)
    io.out.req.valid        := s_is_miss & wait_prewrite | s_is_replace | clean_wr
    io.out.req.bits.addr    := Mux1H(Seq(
        (s_is_miss & single_trans  )    -> single_addr, 
        (s_is_miss & burst_trans   )    -> wburst_addr,
        (s_is_replace & burst_trans)    -> rburst_addr,
        (s_is_replace & single_trans)   -> single_addr,
        s_is_wr_dirtyline               -> cwburst_addr
    ))
    io.out.req.bits.data    := Mux1H(Seq(
        (s_is_miss & single_trans )     -> wdat_r,
        (s_is_miss & burst_trans  )     -> cachedata_out,
        s_is_wr_dirtyline               -> cleandata_out
    ))
    io.out.req.bits.wen     := s_is_miss | s_is_wr_dirtyline
    io.out.req.bits.burst   := burst_trans | s_is_wr_dirtyline
    io.out.req.bits.mask    := Mux(dcacheable  | s_is_wr_dirtyline, Fill(CacheParams.DW/8, true.B), wdat_mask_r)
    io.out.req.bits.len     := Mux(burst_trans | s_is_wr_dirtyline, (CacheParams.BANKS - 1).U, 0.U)
    io.out.req.bits.size    := Mux(burst_trans | s_is_wr_dirtyline, "b10".U, req_size_reg)
    io.out.req.bits.last    := Mux1H(Seq(
        (s_is_miss & burst_trans)     -> bank_cnt_wrap,
        (s_is_miss & ~burst_trans)    -> true.B,
        s_is_replace                  -> true.B,
        s_is_wr_dirtyline             -> bank_cnt_wrap
    ))

    io.out.rsp.ready        := true.B

    io.dxReq.ready := req_inval & inval_done | req_clean & clean_done

    io.dcache_idle          := s_is_idle & wb_is_idle

    val dcache_ck_en       = ~(s_is_idle & ~io.in.req.valid & ~io.dxReq.valid & wb_is_idle)

    val dc_statistics = false

    if(dc_statistics) {
        val fetch_cnt = RegInit(0.U(32.W))
        val hit_cnt   = RegInit(0.U(32.W))
        fetch_cnt := Mux(io.in.req.fire, fetch_cnt + 1.U, fetch_cnt)
        val datap = RegNext(io.in.req.fire, false.B)
        hit_cnt   := Mux(datap & dc_hit & s_is_lookup, hit_cnt + 1.U, hit_cnt)
        
        class DStat extends BlackBox with HasBlackBoxInline {
            val io = IO(new Bundle {
                val all_cnt = Input(UInt(32.W))
                val hit_cnt = Input(UInt(32.W))
            })
            setInline("DStat.v",
            """module DStat(
              | input [31:0] all_cnt,
              | input [31:0] hit_cnt
              |);
              |final begin
              |  $display("--- DCACHE STATISTICS ---");
              |  $display("Total Fetches: %d", all_cnt);
              |  $display("Cache Hits:    %d", hit_cnt);
              |  if (all_cnt > 0) begin
              |    $display("Hit Rate:     %d %%", (hit_cnt * 100) / all_cnt);
              |  end
              |end
              |endmodule
              |""".stripMargin)
        }

        val stat = Module(new DStat)
        stat.io.all_cnt := fetch_cnt
        stat.io.hit_cnt := hit_cnt
    }

    val dcache_debug = false
    if(dcache_debug) {
        val cycle = GTimer()
        when (io.in.req.fire) {
            printf("[%d] DCACHE REQ: addr=0x%x, data=0x%x, wen=%b, size=%d, cache=%b\n", cycle, io.in.req.bits.addr, io.in.req.bits.data, io.in.req.bits.wen, io.in.req.bits.size, io.in.req.bits.cache)
        }
        when (s_is_lookup & dc_hit) {
            printf("[%d] DCACHE HIT: addr=0x%x, data=0x%x idx=0x%x, hit_way=0x%x\n",cycle, req_addr_reg, dc_hit_data, dc_idx_r, Cat(dc_hit_seq.reverse))
        }
        when (s_is_lookup & ~dc_hit & cachemiss_needwb) {
            printf("[%d] DCACHE MISS WB: addr=0x%x\n", cycle, req_addr_reg)
        }
        when (s_is_lookup & ~dc_hit) {
            printf("[%d] DCACHE MISS: idx=0x%x, replace_way=0x%x\n",cycle, dc_idx_r, replace_way)
        }
        when (s_is_miss & replace_bank_cnt === 0.U & io.out.req.fire) {
            printf("[%d] DCACHE WB REQ: addr=0x%x, data=0x%x, wen=%b, size=%d, cache=%b\n", cycle, io.out.req.bits.addr, io.out.req.bits.data, io.out.req.bits.wen, io.out.req.bits.size, dcacheable)
        }
        when (s_is_replace & io.out.req.fire) {
            printf("[%d] DCACHE REPLACE REQ: addr=0x%x, wen=%b, size=%d, cache=%b\n", cycle, io.out.req.bits.addr, io.out.req.bits.wen, io.out.req.bits.size, dcacheable)
        }
        when (s_is_refill & io.out.rsp.fire) {
            printf("[%d] DCACHE REFILL RSP: addr=0x%x, data=0x%x\n", cycle, io.out.req.bits.addr, io.out.rsp.bits.data)
        }
        when (s_is_lookup & ~dc_hit & cachemiss_noneedwb) {
            printf("[%d] DCACHE MISS REPLACE: addr=0x%x\n", cycle, req_addr_reg)
        }

    }

    // for debug
    dontTouch(rw_conflict)
    dontTouch(replace_way_tag)
    dontTouch(wburst_addr)
    dontTouch(wayAllVld)
    dontTouch(dirty_way)
    dontTouch(dc_idx_r)
    dontTouch(cachedata_out)
    dontTouch(dirty_way_nxt)
    dontTouch(dirty_way)
    
}