// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

object Control {

  def genOneHot(bitPosition: Int, width: Int): UInt = {
    require(bitPosition >= 0 && bitPosition < width, "error bitPosition")
    (1 << bitPosition).U(width.W)
  }
  val  IMM_WIDTH = 5
  val BSEL_WIDTH = 4
  val  ALU_WIDTH = 10
  val    J_WIDTH = 9
  val  MEM_WIDTH = 4
  val  REG_WIDTH = 32
  val  CSR_WIDTH = 4
  val   WB_WIDTH = 2
  val  MD_WIDTH  = 5

  val UIMM_BIT = 4
  val IIMM_BIT = 3
  val BIMM_BIT = 2
  val SIMM_BIT = 1
  val JIMM_BIT = 0

  val IMM_U = "b10000".U(IMM_WIDTH.W)
  val IMM_I = "b01000".U(IMM_WIDTH.W)
  val IMM_B = "b00100".U(IMM_WIDTH.W)
  val IMM_S = "b00010".U(IMM_WIDTH.W)
  val IMM_J = "b00001".U(IMM_WIDTH.W)

  val ASEL_REG_BIT = 0
  val ASEL_PC_BIT  = 1
  val ASEL_CSRIMM_BIT  = 2
  val ASEL_Z_BIT   = 3
  val ASEL_WIDTH = 4

  val ASEL_REG    = genOneHot(ASEL_REG_BIT, ASEL_WIDTH)
  val ASEL_PC     = genOneHot(ASEL_PC_BIT,  ASEL_WIDTH)
  val ASEL_CSRIMM = genOneHot(ASEL_CSRIMM_BIT,  ASEL_WIDTH)
  val ASEL_Z      = genOneHot(ASEL_Z_BIT,   ASEL_WIDTH)

  val BSEL_IMM_BIT  = 0
  val BSEL_REG_BIT  = 1
  val BSEL_CSR_BIT  = 2
  val BSEL_FOUR_BIT = 3
  
  val BSEL_IMM  = "b0001".U(BSEL_WIDTH.W)
  val BSEL_REG  = "b0010".U(BSEL_WIDTH.W)
  val BSEL_CSR  = "b0100".U(BSEL_WIDTH.W)
  val BSEL_FOUR = "b1000".U(BSEL_WIDTH.W)
  
  val ALU_ADD    = "b1000000000".U(ALU_WIDTH.W)
  val ALU_SUB    = "b0100000000".U(ALU_WIDTH.W)
  val ALU_AND    = "b0010000000".U(ALU_WIDTH.W)
  val ALU_OR     = "b0001000000".U(ALU_WIDTH.W)
  val ALU_XOR    = "b0000100000".U(ALU_WIDTH.W)
  val ALU_SLL    = "b0000010000".U(ALU_WIDTH.W)
  val ALU_SLT    = "b0000001000".U(ALU_WIDTH.W)
  val ALU_SLTU   = "b0000000100".U(ALU_WIDTH.W)
  val ALU_SRL    = "b0000000010".U(ALU_WIDTH.W)
  val ALU_SRA    = "b0000000001".U(ALU_WIDTH.W)

  val J_JAL_BIT = J_WIDTH - 1
  val J_BEQ_BIT = J_WIDTH - 2
  val J_BNE_BIT = J_WIDTH - 3
  val J_BGE_BIT = J_WIDTH - 4
  val J_BGEU_BIT = J_WIDTH - 5
  val J_BLT_BIT  = J_WIDTH - 6
  val J_BLTU_BIT = J_WIDTH - 7
  val J_JALR_BIT = J_WIDTH - 8
  val J_XXX_BIT  = J_WIDTH - 9

  //We also use JType sigal to indicate slt, sltu ...
  val J_JAL  = "b100000000".U(J_WIDTH.W)
  val J_EQ   = "b010000000".U(J_WIDTH.W)
  val J_NE   = "b001000000".U(J_WIDTH.W)
  val J_GE   = "b000100000".U(J_WIDTH.W)
  val J_GEU  = "b000010000".U(J_WIDTH.W)
  val J_LT   = "b000001000".U(J_WIDTH.W)
  val J_LTU  = "b000000100".U(J_WIDTH.W)
  val J_JALR = "b000000010".U(J_WIDTH.W)
  val J_XXX  = "b000000001".U(J_WIDTH.W)

  // |---L/S---|---Size(2 bit)---|---sign---|
  // signal[3]: 0 -> Load, 1 -> Store
  // signal[2:1]: 0 -> invalid, 1 -> 1 byte, 2 -> 2 bytes, 3 -> 4 bytes
  // signal[0]: 0 -> signed, 1 -> unsigned
  val MEM_LS_BIT = MEM_WIDTH - 1
  val MEM_SIGN_BIT = 0
  val MEM_1B = 0
  val MEM_2B = 2
  val MEM_4B = 3

  val MEM_XXX = "b0000".U(MEM_WIDTH.W) 
  val MEM_SB  = "b1010".U(MEM_WIDTH.W)
  val MEM_SH  = "b1100".U(MEM_WIDTH.W)
  val MEM_SW  = "b1110".U(MEM_WIDTH.W)
  val MEM_LB  = "b0010".U(MEM_WIDTH.W)
  val MEM_LBU = "b0011".U(MEM_WIDTH.W)
  val MEM_LH  = "b0100".U(MEM_WIDTH.W)
  val MEM_LHU = "b0101".U(MEM_WIDTH.W)
  val MEM_LW  = "b0110".U(MEM_WIDTH.W)


  /* the data source of write back */
  val WB_ALU = 0.U(WB_WIDTH.W)
  val WB_CSR = 1.U(WB_WIDTH.W)
  val WB_MEM = 2.U(WB_WIDTH.W)
  val WB_PC4 = 3.U(WB_WIDTH.W)

  val MD_MUL    = "b00001".U(MD_WIDTH.W)
  val MD_MULH   = "b00010".U(MD_WIDTH.W)
  val MD_MULHSU = "b00100".U(MD_WIDTH.W)
  val MD_MULHU  = "b01000".U(MD_WIDTH.W)
  val MD_DIV    = "b10001".U(MD_WIDTH.W)
  val MD_DIVU   = "b10100".U(MD_WIDTH.W)
  val MD_REM    = "b10010".U(MD_WIDTH.W)
  val MD_REMU   = "b11000".U(MD_WIDTH.W)
  val MD_NONE   = "b00000".U(MD_WIDTH.W)

  val CSRRW_BIT = 0
  val CSRRS_BIT = 1
  val CSRRC_BIT = 2
  val  CSRI_BIT = 3

  val CSR_RW    = "b0001".U(CSR_WIDTH.W)
  val CSR_RS    = "b0010".U(CSR_WIDTH.W)
  val CSR_RC    = "b0100".U(CSR_WIDTH.W)
  val CSR_RWI   = "b1001".U(CSR_WIDTH.W)
  val CSR_RSI   = "b1010".U(CSR_WIDTH.W)
  val CSR_RCI   = "b1100".U(CSR_WIDTH.W)
  val CSR_P     = "b1111".U(CSR_WIDTH.W)
  val CSR_N     = "b0000".U(CSR_WIDTH.W)
}

//TODO: use rvdecoderdb
case class InstructionPattern(
  val instType: String,
  val name: String,
  val func7:    BitPat = BitPat.dontCare(7),
  val func3:    BitPat = BitPat.dontCare(3),
  val opcode:   BitPat)
    extends DecodePattern {
  def bitPat: BitPat = pattern

  val pattern = func7 ## BitPat.dontCare(10) ## func3 ## BitPat.dontCare(5) ## opcode

}

import Control._

object ImmSelField extends DecodeField[InstructionPattern, UInt] {
  def name: String = "immediate select"

  def chiselType:                       UInt   = UInt(IMM_WIDTH.W)
  override def default:                 BitPat = BitPat(0.U(IMM_WIDTH.W))
  def genTable(op: InstructionPattern): BitPat = {
    op.instType match {
      case "U" => BitPat(IMM_U)
      case "I" => BitPat(IMM_I)
      case "S" => BitPat(IMM_S)
      case "J" => BitPat(IMM_J)
      case "B" => BitPat(IMM_B)
      case _   => BitPat(IMM_I)
    }
  }
}

object AluOpField extends DecodeField[InstructionPattern, UInt] {
  def name: String = "ALU operation"

  def chiselType: UInt = UInt(ALU_WIDTH.W)
  override def default: BitPat = BitPat(0.U(ALU_WIDTH.W))

  def genTable(op: InstructionPattern): BitPat = {
    val iTypeValue = op.name match {
      case "slti" => ALU_SLT
      case "sltiu" => ALU_SLTU
      case "xori" => ALU_XOR
      case "andi" => ALU_AND
      case "srai" => ALU_SRA
      case "srli" => ALU_SRL
      case "slli" => ALU_SLL
      case "ori"  => ALU_OR
      case _ => ALU_ADD
    }

    val bTypeValue = op.name match {
      case "bltu" => ALU_SLTU 
      case "bgeu" => ALU_SLTU
      case _ => ALU_SUB
    }

    val rTypeValue = op.name match {
      case "add" => ALU_ADD
      case "sub" => ALU_SUB
      case "sll" => ALU_SLL
      case "slt" => ALU_SLT
      case "sltu" => ALU_SLTU
      case "xor" => ALU_XOR
      case "srl" => ALU_SRL
      case "sra" => ALU_SRA
      case "or"  => ALU_OR
      case "and" => ALU_AND
      case _     => ALU_ADD
    }

    val csrTypeValue = op.name match {
      case "csrrw" | "csrrwi" => ALU_ADD
      case "csrrs" | "csrrsi" => ALU_OR
      case "csrrc" | "csrrci" => ALU_AND
      case _ => ALU_ADD
    }

    op.instType match {
      case "U" => BitPat(ALU_ADD)
      case "J" => BitPat(ALU_ADD)
      case "I" => BitPat(iTypeValue)
      case "B" => BitPat(bTypeValue)
      case "S" => BitPat(ALU_ADD)
      case "R" => BitPat(rTypeValue)
      case "CSR" => BitPat(csrTypeValue)
      case _   => BitPat(ALU_ADD)
    }
  }
}

object AselField extends DecodeField[InstructionPattern, UInt] {
  def name:                             String = "ALU select A"
  def chiselType:                       UInt   = UInt(ASEL_WIDTH.W)
  override def default:                 BitPat = BitPat(0.U(ASEL_WIDTH.W))
  def genTable(op: InstructionPattern): BitPat = {
    val uTypeValue = op.name match {
      case "auipc" => ASEL_PC
      case "lui" => ASEL_Z
      case _ => ASEL_Z
    }
    val iJTypeValue = op.name match {
      case "jalr" => ASEL_PC
      case _      => ASEL_REG
    }
    val iCSRTypevalue = op.name match {
      case "csrrw" | "csrrs" | "csrrc" => ASEL_REG
      case "csrrwi" | "csrrsi" | "csrrci" => ASEL_CSRIMM
      case _ => ASEL_Z
    }
    op.instType match {
      case "I" => BitPat(iJTypeValue)
      case "R" => BitPat(ASEL_REG)
      case "B" => BitPat(ASEL_REG)
      case "S" => BitPat(ASEL_REG)
      case "M" => BitPat(ASEL_REG)
      case "U" => BitPat(uTypeValue)
      case "J" => BitPat(ASEL_PC)
      case "CSR" => BitPat(iCSRTypevalue)
      case _   => BitPat(ASEL_Z)
    }
  }
}

object BselField extends DecodeField[InstructionPattern, UInt] {
  def name:                             String = "ALU select B"
  def chiselType:                       UInt   = UInt(BSEL_WIDTH.W)
  override def default:                 BitPat = BitPat(0.U(BSEL_WIDTH.W))
  def genTable(op: InstructionPattern): BitPat = {
    op.instType match {
      case "U" | "S" => BitPat(BSEL_IMM)
      case "I" => if (op.name == "jalr") BitPat(BSEL_FOUR) else BitPat(BSEL_IMM)
      case "J" => BitPat(BSEL_FOUR)
      case "CSR" => BitPat(BSEL_CSR)
      case _: String => BitPat(BSEL_REG) 
    }
  }
}

object JumpField extends DecodeField[InstructionPattern, UInt] {
  def name:       String = "Jump type"
  def chiselType: UInt   = UInt(J_WIDTH.W)
  override def default:  BitPat = BitPat(J_XXX)

  def genTable(op: InstructionPattern): BitPat = {
    val value = op.name match {
      case "jal"  => J_JAL
      case "jalr" => J_JALR
      case "beq"  => J_EQ
      case "bne"  => J_NE
      case "blt"  => J_LT
      case "bltu" => J_LTU
      case "bge"  => J_GE
      case "bgeu" => J_GEU
      case _ => J_XXX
    }

    op.instType match {
      case "J" | "B" | "I" => BitPat(value)
      case _ => BitPat(J_XXX)
    }


  }
}

object MemField extends DecodeField[InstructionPattern, UInt] {
  def name:       String = "Memory related"
  def chiselType: UInt   = UInt(MEM_WIDTH.W)
  override def default: BitPat = BitPat(MEM_XXX)

  def genTable(op: InstructionPattern): BitPat = {
    op.name match {
      case "lb"  => BitPat(MEM_LB)
      case "lh"  => BitPat(MEM_LH)
      case "lw"  => BitPat(MEM_LW)
      case "lbu" => BitPat(MEM_LBU)
      case "lhu" => BitPat(MEM_LHU)
      case "sb"  => BitPat(MEM_SB)
      case "sh"  => BitPat(MEM_SH)
      case "sw"  => BitPat(MEM_SW)
      case _     => BitPat(MEM_XXX)
    }
  }
}

object WBField extends DecodeField[InstructionPattern, UInt] {
  def name:       String = "Write back type"
  def chiselType: UInt   = UInt(2.W)
  override def default: BitPat = BitPat(WB_ALU)

  def genTable(op: InstructionPattern): BitPat = {
    op.name match {
      case "lb"  => BitPat(WB_MEM)
      case "lh"  => BitPat(WB_MEM)
      case "lw"  => BitPat(WB_MEM)
      case "lbu" => BitPat(WB_MEM)
      case "lhu" => BitPat(WB_MEM)
      // case "jalr" => BitPat(WB_PC4) 
      // case "jal" => BitPat(WB_PC4)
      case _ => BitPat(WB_ALU)
    }
  }
}

//Do we really need this signal ?
object WenField extends BoolDecodeField[InstructionPattern] {
  def name:                             String = "write back enable"
  def genTable(op: InstructionPattern): BitPat = {
    if (op.instType == "B" || op.instType == "S") {
      BitPat(false.B)
    }
    else if (op.name == "ecall/ebreak")
      BitPat(false.B) // ecall and ebreak
    else
      BitPat(true.B)
  }
}

object CSRField extends DecodeField[InstructionPattern,UInt] {
  def name:                             String = "csr operations"

  def chiselType: UInt = UInt(CSR_WIDTH.W)
  override def default: BitPat = BitPat(CSR_N)
  
  //
  def genTable(op: InstructionPattern): BitPat = {
    val normal = op.name match {
      case "csrrw" => BitPat(CSR_RW)
      case "csrrs" => BitPat(CSR_RS)
      case "csrrc" => BitPat(CSR_RC)
      case "csrrwi" => BitPat(CSR_RWI)
      case "csrrsi" => BitPat(CSR_RSI)
      case "csrrci" => BitPat(CSR_RCI)
      case _ => BitPat(CSR_N)
    }

    op.instType match {
      case "PRIV" => BitPat(CSR_P)
      case _ => normal
    }
  }
}


object MDField extends DecodeField[InstructionPattern,UInt] {
  def name:                             String = "mul and div operations"

  def chiselType: UInt = UInt(MD_WIDTH.W)
  override def default: BitPat = BitPat(MD_NONE)

  def genTable(op: InstructionPattern): BitPat = {
    val mulDiv = op.name match {
      case "mul"      => BitPat(MD_MUL)
      case "mulh"     => BitPat(MD_MULH)
      case "mulhsu"   => BitPat(MD_MULHSU)
      case "mulhu"    => BitPat(MD_MULHU)
      case "div"      => BitPat(MD_DIV)
      case "divu"     => BitPat(MD_DIVU)
      case "rem"      => BitPat(MD_REM)
      case "remu"     => BitPat(MD_REMU)
      case _          => BitPat(MD_NONE)
    }

    op.instType match {
      case "M" => mulDiv
      case _   => BitPat(MD_NONE)
    }
  }
}

object IllegalField extends BoolDecodeField[InstructionPattern] {
  def name: String = "illegal instruction"

  def genTable(op: InstructionPattern): BitPat = {
    op.instType match {
      case "R" => BitPat(false.B)
      case "J" => BitPat(false.B)
      case "I" => BitPat(false.B)
      case "M" => BitPat(false.B)
      case "B" => BitPat(false.B)
      case "S" => BitPat(false.B)
      case "U" => BitPat(false.B)
      case "CSR" => BitPat(false.B)
      case _ => BitPat(true.B)
    }
  }
}

object FenceiField extends BoolDecodeField[InstructionPattern] {
  def name: String = "fence.i instruction"
  def genTable(op: InstructionPattern): BitPat = {
    if (op.name == "fence")  BitPat(true.B) else BitPat(false.B)
  }
}

class DecoderOutput extends Bundle {

  val immType  = Output(UInt(IMM_WIDTH.W))
  val aluOp    = Output(UInt(ALU_WIDTH.W))
  val aSel     = Output(UInt(ASEL_WIDTH.W))
  val bSel     = Output(UInt(BSEL_WIDTH.W))
  val jType    = Output(UInt(J_WIDTH.W))
  val memType  = Output(UInt(MEM_WIDTH.W))
  val wbType   = Output(UInt(2.W))
  val wbWen    = Output(Bool())
  val csrType  = Output(UInt(CSR_WIDTH.W))
  val muldivOp   = Output(UInt(MD_WIDTH.W))
  val illegal  = Output(Bool())
  val fencei   = Output(Bool())
}

class Cl2Decoder extends Module {

  val io = IO(new Bundle {
    val inst  = Input(UInt(32.W))
    val out   = Output(new DecoderOutput())
  })

  val decodeTable  = new DecodeTable(Cl2DecodeInfo.possiblePatterns, Cl2DecodeInfo.allFields)
  val decodeResult = decodeTable.decode(io.inst)
  
  //TODO: use an elegant way to get decoder output
  io.out.immType := decodeResult(ImmSelField)
  io.out.aluOp   := decodeResult(AluOpField)
  io.out.aSel    := decodeResult(AselField)
  io.out.bSel    := decodeResult(BselField)
  io.out.jType   := decodeResult(JumpField)
  io.out.memType := decodeResult(MemField)
  io.out.wbType  := decodeResult(WBField)
  io.out.wbWen   := decodeResult(WenField)
  io.out.csrType := decodeResult(CSRField)
  io.out.muldivOp  := decodeResult(MDField)
  io.out.illegal := decodeResult(IllegalField)
  io.out.fencei  := decodeResult(FenceiField)



}
