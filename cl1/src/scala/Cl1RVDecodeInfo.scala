// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode

//TODO: Use rvdeocderdb
object Cl2DecodeInfo {
  val possiblePatterns = Seq(
    InstructionPattern(instType = "U", name = "lui",  opcode = BitPat("b0110111")),                        
    InstructionPattern(instType = "U", name = "auipc",opcode = BitPat("b0010111")),                        
    InstructionPattern(instType = "J", name = "jal",  opcode = BitPat("b1101111")),                        
    InstructionPattern(instType = "I", name = "jalr", func3 = BitPat("b000"), opcode = BitPat("b1100111")),
    InstructionPattern(instType = "B", name = "beq",  func3 = BitPat("b000"), opcode = BitPat("b1100011")),
    InstructionPattern(instType = "B", name = "bne",  func3 = BitPat("b001"), opcode = BitPat("b1100011")),
    InstructionPattern(instType = "B", name = "blt",  func3 = BitPat("b100"), opcode = BitPat("b1100011")),
    InstructionPattern(instType = "B", name = "bge",  func3 = BitPat("b101"), opcode = BitPat("b1100011")),
    InstructionPattern(instType = "B", name = "bltu", func3 = BitPat("b110"), opcode = BitPat("b1100011")),
    InstructionPattern(instType = "B", name = "bgeu", func3 = BitPat("b111"), opcode = BitPat("b1100011")),
    InstructionPattern(instType = "I", name = "lb",   func3 = BitPat("b000"), opcode = BitPat("b0000011")),
    InstructionPattern(instType = "I", name = "lh",   func3 = BitPat("b001"), opcode = BitPat("b0000011")),
    InstructionPattern(instType = "I", name = "lw",   func3 = BitPat("b010"), opcode = BitPat("b0000011")),
    InstructionPattern(instType = "I", name = "lbu",  func3 = BitPat("b100"), opcode = BitPat("b0000011")),
    InstructionPattern(instType = "I", name = "lhu",  func3 = BitPat("b101"), opcode = BitPat("b0000011")),
    InstructionPattern(instType = "S", name = "sb",   func3 = BitPat("b000"), opcode = BitPat("b0100011")),
    InstructionPattern(instType = "S", name = "sh",   func3 = BitPat("b001"), opcode = BitPat("b0100011")),
    InstructionPattern(instType = "S", name = "sw",   func3 = BitPat("b010"), opcode = BitPat("b0100011")),
    InstructionPattern(instType = "I", name = "addi", func3 = BitPat("b000"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "slti", func3 = BitPat("b010"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "sltiu",func3 = BitPat("b011"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "xori", func3 = BitPat("b100"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "ori",  func3 = BitPat("b110"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "andi", func3 = BitPat("b111"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "slli", func7 = BitPat("b0000000"), func3 = BitPat("b001"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "srli", func7 = BitPat("b0000000"), func3 = BitPat("b101"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "I", name = "srai", func7 = BitPat("b0100000"), func3 = BitPat("b101"), opcode = BitPat("b0010011")),
    InstructionPattern(instType = "R", name = "add",  func7 = BitPat("b0000000"), func3 = BitPat("b000"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "sub",  func7 = BitPat("b0100000"), func3 = BitPat("b000"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "sll",  func7 = BitPat("b0000000"), func3 = BitPat("b001"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "slt",  func7 = BitPat("b0000000"), func3 = BitPat("b010"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "sltu", func7 = BitPat("b0000000"), func3 = BitPat("b011"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "xor",  func7 = BitPat("b0000000"), func3 = BitPat("b100"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "srl",  func7 = BitPat("b0000000"), func3 = BitPat("b101"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "sra",  func7 = BitPat("b0100000"), func3 = BitPat("b101"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "or",   func7 = BitPat("b0000000"), func3 = BitPat("b110"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "R", name = "and",  func7 = BitPat("b0000000"), func3 = BitPat("b111"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "mul",   func7 = BitPat("b0000001"), func3 = BitPat("b000"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "mulh",  func7 = BitPat("b0000001"), func3 = BitPat("b001"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "mulhsu",func7 = BitPat("b0000001"), func3 = BitPat("b010"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "mulhu", func7 = BitPat("b0000001"), func3 = BitPat("b011"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "div",   func7 = BitPat("b0000001"), func3 = BitPat("b100"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "divu",  func7 = BitPat("b0000001"), func3 = BitPat("b101"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "rem",   func7 = BitPat("b0000001"), func3 = BitPat("b110"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "M", name = "remu",  func7 = BitPat("b0000001"), func3 = BitPat("b111"), opcode = BitPat("b0110011")),
    InstructionPattern(instType = "CSR", name = "csrrw",  func3 = BitPat("b001"),  opcode = BitPat("b1110011")),
    InstructionPattern(instType = "CSR", name = "csrrs",  func3 = BitPat("b010"),  opcode = BitPat("b1110011")),
    InstructionPattern(instType = "CSR", name = "csrrc",  func3 = BitPat("b011"),  opcode = BitPat("b1110011")),
    InstructionPattern(instType = "CSR", name = "csrrwi", func3 = BitPat("b101"), opcode = BitPat("b1110011")),
    InstructionPattern(instType = "CSR", name = "csrrsi", func3 = BitPat("b110"), opcode = BitPat("b1110011")),
    InstructionPattern(instType = "CSR", name = "csrrci", func3 = BitPat("b111"), opcode = BitPat("b1110011")),
    InstructionPattern(instType = "R", name = "fence",func3 = BitPat("b001"), opcode = BitPat("b0001111")),                    // FENCE
    InstructionPattern(instType = "PRIV", name = "ecall/ebreak", func3 = BitPat("b000"), opcode = BitPat("b1110011")) //  ECALL/EBREAK

  )

  val allFields = Seq(
    ImmSelField,
    AluOpField,
    AselField,
    BselField,
    JumpField,
    MemField,
    WBField,
    WenField,
    CSRField,
    MDField,
    IllegalField,
    FenceiField
  )

}
