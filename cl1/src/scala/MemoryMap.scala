package cl1

import chisel3._
import chisel3.util._

object MemoryMap {

  // --- 地址范围常量 (32位地址) ---
  val ISRAM_START = "h0100_0000".U(32.W)
  val ISRAM_END   = "h0103_FFFF".U(32.W)

  val DSRAM_START = "h0180_0000".U(32.W)
  val DSRAM_END   = "h0180_3FFF".U(32.W)

  val PLIC_START  = "h0C00_0000".U(32.W)
  val PLIC_END    = "h0FFF_FFFF".U(32.W)

  val CLINT_START = "h0200_0000".U(32.W)
  val CLINT_END   = "h020B_FFFF".U(32.W)

  val UART_START  = "h1001_0000".U(32.W)
  val UART_END    = "h1001_0FFF".U(32.W)

  val QSPI_MEM_START = "h2000_0000".U(32.W)
  val QSPI_MEM_END   = "h20FF_FFFF".U(32.W) // 16M 闪存

  val TIMER_START = "h1008_0000".U(32.W)
  val TIMER_END   = "h1008_0FFF".U(32.W)

  val GPIO_START  = "h1006_0000".U(32.W)
  val GPIO_END    = "h1006_0FFF".U(32.W)

  val QSPI_REG_START = "h1004_0000".U(32.W) // QSPI 控制寄存器
  val QSPI_REG_END   = "h1004_0FFF".U(32.W)

  val SDRAM_START = "h8000_0000".U(32.W)
  val SDRAM_END   = "h9FFF_FFFF".U(32.W) // 512M 内存

  val CRU_START   = "h100A_0000".U(32.W)
  val CRU_END     = "h100A_0FFF".U(32.W)

  val DEBUG_START = "h0000_0000".U(32.W)
  val DEBUG_END   = "h0000_0FFF".U(32.W) // (基于 0x0-0x0FFF)

  val I2C_START   = "h1003_0000".U(32.W)
  val I2C_END     = "h1003_0FFF".U(32.W)


  def isRegion(addr: UInt, start: UInt, end: UInt): Bool = {
    (addr >= start) && (addr <= end)
  }


  def isISRAM(addr: UInt)   = isRegion(addr, ISRAM_START, ISRAM_END)
  def isDSRAM(addr: UInt)   = isRegion(addr, DSRAM_START, DSRAM_END)
  def isSDRAM(addr: UInt)   = isRegion(addr, SDRAM_START, SDRAM_END)
  def isQSPIMem(addr: UInt) = isRegion(addr, QSPI_MEM_START, QSPI_MEM_END)
  
  val mmioRegions = List(
    (PLIC_START, PLIC_END),
    (CLINT_START, CLINT_END),
    (UART_START, UART_END),
    (TIMER_START, TIMER_END),
    (GPIO_START, GPIO_END),
    (QSPI_REG_START, QSPI_REG_END),
    (CRU_START, CRU_END),
    (DEBUG_START, DEBUG_END),
    (I2C_START, I2C_END)
  )

  def isMMIO(addr: UInt): Bool = {
    mmioRegions.map { case (start, end) => isRegion(addr, start, end) }
               .reduce(_ || _)
  }


  
  def isICacheable(addr: UInt): Bool = {
    isSDRAM(addr) || isQSPIMem(addr) || isISRAM(addr)
  }

  def isDCacheable(addr: UInt): Bool = {
    isSDRAM(addr) || isDSRAM(addr) || isQSPIMem(addr) || isISRAM(addr)
  }
}

object SimpleSocMemoryMap {

  // --- 地址范围常量 (32位地址) ---
  val RAM_START = "h8000_0000".U(32.W)
  val RAM_END   = "h80FF_FFFF".U(32.W) // 16MB RAM

  val DEBUG_START = "h0000_0000".U(32.W)
  val DEBUG_END   = "h0000_0FFF".U(32.W) // 4K Debug 模块

  val UART_START = "h1000_0000".U(32.W)
  val UART_END   = "h1000_0FFF".U(32.W) // 4K UART 模块


  def isRegion(addr: UInt, start: UInt, end: UInt): Bool = {
    (addr >= start) && (addr <= end)
  }

  def isRAM(addr: UInt)   = isRegion(addr, RAM_START, RAM_END)
  def isDebug(addr: UInt) = isRegion(addr, DEBUG_START, DEBUG_END)
  def isUART(addr: UInt)  = isRegion(addr, UART_START, UART_END)
  
  val mmioRegions = List(
    (DEBUG_START, DEBUG_END),
    (UART_START, UART_END)
  )


  def isMMIO(addr: UInt): Bool = {
    if (mmioRegions.isEmpty) {
      false.B
    } else {
      mmioRegions.map { case (start, end) => isRegion(addr, start, end) }
                 .reduce(_ || _)
    }
  }


  def isICacheable(addr: UInt): Bool = {
    isRAM(addr)
  }


  def isDCacheable(addr: UInt): Bool = {
    isRAM(addr)
  }
}