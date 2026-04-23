package cl1

import chisel3._
import chisel3.util._


class Difftest extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val rst_n = Input(Reset())

    val diff_commit = Input(Bool())
    val diff_skip   = Input(Bool())
    val diff_pc     = Input(UInt(32.W))
    val diff_inst   = Input(UInt(32.W))
    val diff_wen    = Input(UInt(16.W))
    val diff_rdIdx  = Input(UInt(5.W))
    val diff_wdata  = Input(UInt(32.W))

    val diff_csr_wen   = Input(Bool())
    val diff_csr_wdata = Input(UInt(32.W))
    val diff_csr_waddr = Input(UInt(12.W))
  })
}

//TODO: Add abstract device to spike
class Difftest1 extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val pc     = Input(UInt(32.W))
    val inst  = Input(UInt(32.W))
    val c_inst = Input(UInt(16.W))
    val is_c_inst = Input(Bool())
    val commit = Input(Bool())
    val skip   = Input(Bool())
    val clock  = Input(Clock())
    val reset  = Input(Reset())
  })
  setInline(
    "difftest.sv",
    """
    module Difftest(
      input logic [31:0] pc,
      input logic [31:0] inst,
      input logic [15:0] c_inst,
      input logic is_c_inst,
      input logic skip,
      input logic commit,
      input logic clock,
      input logic reset
    );
    
    import "DPI-C" function int difftest_step(input int pc, input int inst, input int c_inst, input int is_c_inst);
    import "DPI-C" function void difftest_skip(input int pc, input int is_c_inst);

    int ret;
    reg commit_r;
    reg [31:0] pc_r;
    reg [31:0] inst_r;
    reg [15:0] c_inst_r;
    reg is_c_inst_r;
    reg skip_r;

    always_ff @(posedge clock) begin
      if(reset) begin
        commit_r <= 0;
        pc_r <= 0;
        inst_r <= 0;
        c_inst_r <= 0;
        is_c_inst_r <= 0;
        skip_r <= 0;
      end
      else begin
        commit_r <= commit;
        pc_r <= pc;
        inst_r <= inst;
        c_inst_r <= c_inst;
        is_c_inst_r <= is_c_inst;
        skip_r <= skip;
      end
    end

    always_ff @(posedge clock) begin
      if(commit_r & !reset) begin
        if(!skip_r) begin
          ret = difftest_step(pc_r, inst_r, c_inst_r, is_c_inst_r);
          if(ret) begin
            $fatal("HIT BAD TRAP!");
          end
        end else begin
          difftest_skip(pc_r, is_c_inst_r);
        end
      end
    end
  endmodule
    """
  )
}
