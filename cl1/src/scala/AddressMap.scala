// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._

case class AddressRegion(
    name: String,
    base: BigInt,
    size: BigInt,
    mmio: Boolean = false,
    iCacheable: Boolean = false,
    dCacheable: Boolean = false) {
  require(size > 0, s"address region '$name' must have non-zero size")

  val end: BigInt = base + size - 1

  def contains(addr: UInt): Bool =
    (addr >= base.U(32.W)) && (addr <= end.U(32.W))
}

case class PlatformAddressMap(
    name: String,
    bootAddr: BigInt,
    trapVector: BigInt,
    regions: Seq[AddressRegion],
    roles: Map[String, String]) {
  private val regionsByName = regions.map(region => region.name -> region).toMap

  def bootAddrLiteral: String = hexLiteral(bootAddr)
  def trapVectorLiteral: String = hexLiteral(trapVector)

  def region(name: String): Option[AddressRegion] = regionsByName.get(name)

  def roleRegion(role: String): Option[AddressRegion] =
    roles.get(role).flatMap(region)

  def containsRegion(addr: UInt, name: String): Bool =
    region(name).map(_.contains(addr)).getOrElse(false.B)

  def containsRole(addr: UInt, role: String): Bool =
    roleRegion(role).map(_.contains(addr)).getOrElse(false.B)

  def isMMIO(addr: UInt): Bool =
    any(regions.filter(_.mmio), addr)

  def isICacheable(addr: UInt): Bool =
    any(regions.filter(_.iCacheable), addr)

  def isDCacheable(addr: UInt): Bool =
    any(regions.filter(_.dCacheable), addr)

  private def any(candidates: Seq[AddressRegion], addr: UInt): Bool =
    candidates.map(_.contains(addr)).reduceOption((lhs, rhs) => lhs || rhs).getOrElse(false.B)

  private def hexLiteral(value: BigInt): String =
    f"h${value.toLong & 0xffffffffL}%08x"
}

object PlatformAddressMaps {
  val SimpleSoc = PlatformAddressMap(
    name = "simple_soc",
    bootAddr = BigInt("80000000", 16),
    trapVector = BigInt("20000000", 16),
    roles = Map(
      "ram" -> "ram",
      "load" -> "ram",
      "uart" -> "uart",
      "host_exit" -> "host_exit",
      "debug" -> "debug"
    ),
    regions = Seq(
      AddressRegion(
        name = "ram",
        base = BigInt("80000000", 16),
        size = BigInt("01000000", 16),
        iCacheable = true,
        dCacheable = true
      ),
      AddressRegion(
        name = "debug",
        base = BigInt("00000000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "uart",
        base = BigInt("10000000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "host_exit",
        base = BigInt("10000004", 16),
        size = BigInt("00000004", 16),
        mmio = true
      )
    )
  )

  val FullSoc = PlatformAddressMap(
    name = "full_soc",
    bootAddr = BigInt("01000000", 16),
    trapVector = BigInt("20000000", 16),
    roles = Map(
      "ram" -> "sdram",
      "load" -> "isram",
      "uart" -> "uart",
      "debug" -> "debug"
    ),
    regions = Seq(
      AddressRegion(
        name = "isram",
        base = BigInt("01000000", 16),
        size = BigInt("00040000", 16),
        iCacheable = true,
        dCacheable = true
      ),
      AddressRegion(
        name = "dsram",
        base = BigInt("01800000", 16),
        size = BigInt("00004000", 16),
        dCacheable = true
      ),
      AddressRegion(
        name = "plic",
        base = BigInt("0c000000", 16),
        size = BigInt("04000000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "clint",
        base = BigInt("02000000", 16),
        size = BigInt("000c0000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "uart",
        base = BigInt("10010000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "qspi_mem",
        base = BigInt("20000000", 16),
        size = BigInt("01000000", 16),
        iCacheable = true,
        dCacheable = true
      ),
      AddressRegion(
        name = "timer",
        base = BigInt("10080000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "gpio",
        base = BigInt("10060000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "qspi_reg",
        base = BigInt("10040000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "sdram",
        base = BigInt("80000000", 16),
        size = BigInt("20000000", 16),
        iCacheable = true,
        dCacheable = true
      ),
      AddressRegion(
        name = "cru",
        base = BigInt("100a0000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "debug",
        base = BigInt("00000000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      ),
      AddressRegion(
        name = "i2c",
        base = BigInt("10030000", 16),
        size = BigInt("00001000", 16),
        mmio = true
      )
    )
  )

  val all: Map[String, PlatformAddressMap] =
    Seq(SimpleSoc, FullSoc).map(platform => platform.name -> platform).toMap

  def apply(name: String): PlatformAddressMap =
    all.getOrElse(name, throw new IllegalArgumentException(s"unknown CL1 platform '$name'"))

  def selected: PlatformAddressMap = apply(Cl1BuildMode.PLATFORM)
}
