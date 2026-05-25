SHELL := /usr/bin/env bash

SCRIPT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(SCRIPT_DIR)/..)
CL1_TEST_MODE ?= bus
CL1_SIM := $(SCRIPT_DIR)/cl1_sim.py
TESTS ?= $(SCRIPT_DIR)/../tests
RVDV_ARGS ?=
SIM_ARGS ?=

.PHONY: build selftest run test regression check riscv-dv clean

build:
	$(CL1_SIM) build --mode $(CL1_TEST_MODE)

selftest:
	$(CL1_SIM) selftest --mode $(CL1_TEST_MODE)

run:
	@if [ -z "$(IMAGE)" ]; then \
		echo "Usage: make -C sim_verilator run IMAGE=/path/to/test.bin [SIM_ARGS='...']" >&2; \
		exit 1; \
	fi
	cd $(ROOT_DIR) && $(CL1_SIM) run --mode $(CL1_TEST_MODE) -- $(SIM_ARGS) $(IMAGE)

test:
	$(CL1_SIM) test --mode $(CL1_TEST_MODE) $(TESTS)

regression:
	$(CL1_SIM) regression --mode $(CL1_TEST_MODE) --suite $(if $(SUITE),$(SUITE),smoke)

check:
	$(CL1_SIM) check --mode $(CL1_TEST_MODE) --level $(if $(LEVEL),$(LEVEL),full)

riscv-dv:
	$(CL1_SIM) riscv-dv --mode $(CL1_TEST_MODE) $(RVDV_ARGS) $(if $(SUITE),$(SUITE),)

clean:
	$(CL1_SIM) clean
