// SPDX-License-Identifier: MulanPSL-2.0

package cl1

private object ConfigOverrides {
  private def get(name: String): Option[String] =
    sys.props.get(name).orElse(sys.env.get(name))

  def bool(name: String, default: Boolean): Boolean = {
    get(name).map(_.trim.toLowerCase) match {
      case None => default
      case Some("1" | "true" | "yes" | "y" | "on") => true
      case Some("0" | "false" | "no" | "n" | "off") => false
      case Some(value) =>
        throw new IllegalArgumentException(s"$name must be a boolean value, got '$value'")
    }
  }

  def int(name: String, default: Int): Int =
    get(name).map(_.trim).filter(_.nonEmpty).map(_.toInt).getOrElse(default)
}

object globalConfig {
  val syn = ConfigOverrides.bool("CL1_GLOBAL_SYN", true)
  val simpleSocTest = ConfigOverrides.bool("CL1_GLOBAL_SIMPLE_SOC_TEST", true)
  val fullSocTest  = ConfigOverrides.bool("CL1_GLOBAL_FULL_SOC_TEST", false)
  // require (
  //   Seq(syn, simpleSocTest, fullSocTest).count(_ == true) == 1, 
  //   "Error: Exactly one of 'syn', 'smipleSocTest', or 'fullSocTest' must be set to true in globalConfig."
  // )
}

object Cl1Config {
  val BOOT_ADDR  = if(globalConfig.fullSocTest) "h01000000" else "h80000000"
  val TVEC_ADDR  = "h20000000"
  val BUS_WIDTH  = 32
  val CKG_EN     = false
  val difftest   = if(globalConfig.simpleSocTest) false else false
  val DBG_ENTRYADDR = "h800"
  val DBG_EXCP_BASE = "h800"
  val MDU_SHAERALU = false
  val WB_PIPESTAGE = true
  val HAS_ICACHE   = ConfigOverrides.bool("CL1_HAS_ICACHE", false)
  val HAS_DCACHE   = ConfigOverrides.bool("CL1_HAS_DCACHE", false)
  val RST_ACTIVELOW = true
  val RST_ASYNC     = true
  val SOC_DIFF     = if(globalConfig.fullSocTest) true else false
  val SramFoundary = ConfigOverrides.bool("CL1_SRAM_FOUNDARY", if(globalConfig.syn || globalConfig.fullSocTest) true else false)
  val SOC_D64      = if(globalConfig.fullSocTest) true else false
  val Technology   = "SMIC110"

  val FORMAL_VERIF = true
  val RISCV_FORMAL_ALTOPS = true
  val EXPOSE_CORE_BUS = ConfigOverrides.bool("CL1_EXPOSE_CORE_BUS", true)  // When true, expose CoreBus (fetch + mem) instead of AXI4 at top level
  val FORMAL_CACHE_IDXW = ConfigOverrides.int("CL1_FORMAL_CACHE_IDXW", 7)
}

object Cl1PowerSaveConfig {
  val MODPOWERCFG = false
  val MDU_CKG_EN  = if (MODPOWERCFG) true else false
  val DCACHE_CKG_EN = if (MODPOWERCFG) true else false
  val LSU_CKG_EN    = if (MODPOWERCFG) true else false
  val RF_NORESET    = true
}
