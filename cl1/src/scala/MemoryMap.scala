package cl1

import chisel3._

object MemoryMap {
  private def selected: PlatformAddressMap = PlatformAddressMaps.selected

  def isRegion(addr: UInt, start: UInt, end: UInt): Bool =
    (addr >= start) && (addr <= end)

  def isRAM(addr: UInt): Bool = selected.containsRole(addr, "ram")
  def isDebug(addr: UInt): Bool = selected.containsRole(addr, "debug")
  def isUART(addr: UInt): Bool = selected.containsRole(addr, "uart")
  def isHostExit(addr: UInt): Bool = selected.containsRole(addr, "host_exit")

  def isISRAM(addr: UInt): Bool = selected.containsRegion(addr, "isram")
  def isDSRAM(addr: UInt): Bool = selected.containsRegion(addr, "dsram")
  def isSDRAM(addr: UInt): Bool = selected.containsRegion(addr, "sdram")
  def isQSPIMem(addr: UInt): Bool = selected.containsRegion(addr, "qspi_mem")
  def isMMIO(addr: UInt): Bool = selected.isMMIO(addr)
  def isICacheable(addr: UInt): Bool = selected.isICacheable(addr)
  def isDCacheable(addr: UInt): Bool = selected.isDCacheable(addr)
}

object SimpleSocMemoryMap {
  def isRegion(addr: UInt, start: UInt, end: UInt): Bool = MemoryMap.isRegion(addr, start, end)

  def isRAM(addr: UInt): Bool = MemoryMap.isRAM(addr)
  def isDebug(addr: UInt): Bool = MemoryMap.isDebug(addr)
  def isUART(addr: UInt): Bool = MemoryMap.isUART(addr)
  def isHostExit(addr: UInt): Bool = MemoryMap.isHostExit(addr)
  def isMMIO(addr: UInt): Bool = MemoryMap.isMMIO(addr)
  def isICacheable(addr: UInt): Bool = MemoryMap.isICacheable(addr)
  def isDCacheable(addr: UInt): Bool = MemoryMap.isDCacheable(addr)
}
