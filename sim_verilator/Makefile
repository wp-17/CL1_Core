SHELL := /usr/bin/env bash

SCRIPT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(SCRIPT_DIR)/..)
SIM_BIN := $(SCRIPT_DIR)/build/cl1_verilator
TESTS ?= $(SCRIPT_DIR)/../tests
RVDV_ARGS ?=

.PHONY: build selftest run test regression riscv-dv clean

build:
	$(SCRIPT_DIR)/build.sh

selftest:
	$(SCRIPT_DIR)/build_test_programs.sh

run: build
	@if [ -z "$(IMAGE)" ]; then \
		echo "Usage: make -C sim_verilator run IMAGE=/path/to/test.bin [SIM_ARGS='...']" >&2; \
		exit 1; \
	fi
	$(SIM_BIN) $(SIM_ARGS) $(IMAGE)

test: build
	$(SCRIPT_DIR)/run_tests.py $(TESTS)

regression: build selftest
	$(SCRIPT_DIR)/regression.py --suite $(if $(SUITE),$(SUITE),smoke)

riscv-dv: build
	cd $(ROOT_DIR) && $(SCRIPT_DIR)/run_riscv_dv.py $(if $(SUITE),$(SUITE),) $(RVDV_ARGS)

clean:
	rm -rf $(SCRIPT_DIR)/build
	rm -rf $(SCRIPT_DIR)/selftest/build
