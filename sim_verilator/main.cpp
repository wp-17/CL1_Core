#include <exception>
#include <iostream>
#include <utility>

#include <verilated.h>

#include "options.h"
#include "simulator.h"

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);

  try {
    cl1sim::Options options = cl1sim::parse_args(argc, argv);
    cl1sim::Simulator simulator(std::move(options));
    return simulator.run();
  } catch (const std::exception& ex) {
    std::cerr << "[sim] argument error: " << ex.what() << "\n";
    cl1sim::print_usage(argv[0]);
    return 1;
  }
}
