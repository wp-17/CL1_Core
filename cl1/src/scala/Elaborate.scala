object Elaborate extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _),
    "--disable-all-randomization",
    // "-o=vsrc/sv-gen",
    // "--split-verilog"
    "--ckg-name=HVT_CLKLANQHDV4",
    "--ckg-test-enable=TE",
    "--ckg-input=CK",
    "--ckg-enable=E",
    "--ckg-output=Q"
  )
  circt.stage.ChiselStage.emitSystemVerilogFile(new cl1.Cl1Top(), args, firtoolOptions)
}
  