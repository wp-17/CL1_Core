// SPDX-License-Identifier: MulanPSL-2.0

package cl1

object globalConfig {
  val syn = true
  val simpleSocTest = true
  val fullSocTest  = false
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
  val HAS_ICACHE   = false
  val HAS_DCACHE   = false
  val RST_ACTIVELOW = true
  val RST_ASYNC     = true
  val SOC_DIFF     = if(globalConfig.fullSocTest) true else false
  val SramFoundary = if(globalConfig.syn || globalConfig.fullSocTest) true else false
  val SOC_D64      = if(globalConfig.fullSocTest) true else false
  val Technology   = "SMIC110"

  val FORMAL_VERIF = true
  val EXPOSE_CORE_BUS = true  // When true, expose CoreBus (fetch + mem) instead of AXI4 at top level
}

object Cl1PowerSaveConfig {
  val MODPOWERCFG = false
  val MDU_CKG_EN  = if (MODPOWERCFG) true else false
  val DCACHE_CKG_EN = if (MODPOWERCFG) true else false
  val LSU_CKG_EN    = if (MODPOWERCFG) true else false
  val RF_NORESET    = true
}
