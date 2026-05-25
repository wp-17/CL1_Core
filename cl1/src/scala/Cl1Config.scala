// SPDX-License-Identifier: MulanPSL-2.0

package cl1

object Cl1BuildMode {
  private def configValue(name: String): Option[String] = {
    sys.props.get(name).orElse(sys.env.get(name)).map(_.trim).filter(_.nonEmpty)
  }

  private def boolValue(name: String, default: Boolean): Boolean = {
    configValue(name) match {
      case Some(value) =>
        value.toLowerCase match {
          case "1" | "true" | "yes" | "y" | "on"  => true
          case "0" | "false" | "no" | "n" | "off" => false
          case other => throw new IllegalArgumentException(s"$name must be boolean, got '$other'")
        }
      case None => default
    }
  }

  private def normalizePlatform(value: String): String = {
    value.toLowerCase.replace("-", "_") match {
      case "simple" | "simple_soc" => "simple_soc"
      case "full" | "full_soc" => "full_soc"
      case other => throw new IllegalArgumentException(s"CL1_PLATFORM must be simple_soc or full_soc, got '$other'")
    }
  }

  val TEST_MODE: String = configValue("cl1.testMode")
    .orElse(configValue("CL1_TEST_MODE"))
    .getOrElse("bus")
    .toLowerCase

  require(
    TEST_MODE == "bus" || TEST_MODE == "cache",
    s"CL1_TEST_MODE must be 'bus' or 'cache', got '$TEST_MODE'"
  )

  val CACHE_MODE: Boolean = TEST_MODE == "cache"

  private val legacyFullSoc = boolValue("CL1_FULL_SOC_TEST", false)
  private val legacySimpleSoc = boolValue("CL1_SIMPLE_SOC_TEST", true)
  val PLATFORM: String = configValue("CL1_PLATFORM")
    .orElse(configValue("CL1_ADDRESS_PROFILE"))
    .map(normalizePlatform)
    .getOrElse(if (legacyFullSoc || !legacySimpleSoc) "full_soc" else "simple_soc")

  def bool(name: String, default: Boolean): Boolean = boolValue(name, default)
}

object globalConfig {
  val syn = Cl1BuildMode.bool("CL1_SYN", !Cl1BuildMode.CACHE_MODE)
  val simpleSocTest = Cl1BuildMode.PLATFORM == "simple_soc"
  val fullSocTest  = Cl1BuildMode.PLATFORM == "full_soc"
}

object Cl1Config {
  private val platform = PlatformAddressMaps.selected
  val BOOT_ADDR  = platform.bootAddrLiteral
  val TVEC_ADDR  = platform.trapVectorLiteral
  val BUS_WIDTH  = 32
  val CKG_EN     = false
  val difftest   = if(globalConfig.simpleSocTest) false else false
  val DBG_ENTRYADDR = "h800"
  val DBG_EXCP_BASE = "h800"
  val MDU_SHAERALU = false
  val WB_PIPESTAGE = true
  val HAS_ICACHE   = Cl1BuildMode.bool("CL1_HAS_ICACHE", Cl1BuildMode.CACHE_MODE)
  val HAS_DCACHE   = Cl1BuildMode.bool("CL1_HAS_DCACHE", Cl1BuildMode.CACHE_MODE)
  val RST_ACTIVELOW = true
  val RST_ASYNC     = true
  val SOC_DIFF     = if(globalConfig.fullSocTest) true else false
  val SramFoundary = Cl1BuildMode.bool(
    "CL1_SRAM_FOUNDARY",
    if (Cl1BuildMode.CACHE_MODE) false else globalConfig.syn || globalConfig.fullSocTest
  )
  val SOC_D64      = if(globalConfig.fullSocTest) true else false
  val Technology   = "SMIC110"

  val FORMAL_VERIF = true
  val RISCV_FORMAL_ALTOPS = false
  val EXPOSE_CORE_BUS = Cl1BuildMode.bool("CL1_EXPOSE_CORE_BUS", !Cl1BuildMode.CACHE_MODE)

  require(
    !(EXPOSE_CORE_BUS && (HAS_ICACHE || HAS_DCACHE)),
    "cache instances are unreachable when EXPOSE_CORE_BUS=true; use CL1_TEST_MODE=cache or disable caches"
  )
}

object Cl1PowerSaveConfig {
  val MODPOWERCFG = false
  val MDU_CKG_EN  = if (MODPOWERCFG) true else false
  val DCACHE_CKG_EN = if (MODPOWERCFG) true else false
  val LSU_CKG_EN    = if (MODPOWERCFG) true else false
  val RF_NORESET    = true
}
