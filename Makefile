# Project Configuration
PRJ       := cl1
PRJ_DIR   := $(CURDIR)
BUILD_DIR := ./build/
WAVE_DIR  := ./wave
SAIF_DIR  := ./saif
VSRC_DIR  := ./vsrc
CPUTOP    := Cl1Top
DUMP_WAVE :=
PWR_ANALYSIS :=1

CONFIG_DBG = n
CONFIG_NETSIM = n

POSTSIM =1
SDF_ON  :=1
#POSTSIM_PATH = /nfs/share/home/wangpian/cl1/backend/Flow_S110/bes_data/sta/sdf/Cl1Top_V3_2026-01-16_20-38_CTS_MAX_CMAX_SDF_Jan_16_20
POSTSIM_PATH = /nfs/share/home/wangpian/cl1/backend/Flow_S110/bes_data/sta/sdf/Cl1Top_Vbase2_2026-02-23_20-37_CTS_MAX_CMAX_SAIF_SDF_Feb_23_20
CPU_DIR := $(if $(POSTSIM), $(POSTSIM_PATH), \
           $(if $(filter y, $(CONFIG_NETSIM)), ./soc/core_netlist, $(VSRC_DIR)))

$(info ========================================================)
$(info   > POSTSIM Mode: $(if $(POSTSIM), ON, OFF))
$(info   > CPU_DIR Path: $(CPU_DIR))
$(info ========================================================)

NETSIM_VZ_FILE := /nfs/share/home/wangpian/cl1/backend/Flow_S110/bes_data/syn/netlist/Cl1Top_FINM100VPOWER20260114_1623.syn.v.gz

ifeq ($(strip $(CONFIG_NETSIM)), y)
	 _AUTO_UNZIP_VZ := $(shell \
	 	if [ -f $(NETSIM_VZ_FILE) ]; then \
			echo "Wait... Decoompressing verilog netlist: $(NETSIM_VZ_FILE)"; \
			gzip -cd $(NETSIM_VZ_FILE) > ./soc/core_netlist/Cl1Top_netlist.v; \
		else \
			echo "Warning: Verilog netlist .gz file not found at $(NETSIM_VZ_FILE)"; \
		fi \
	 )
endif

ifneq ($(strip $(POSTSIM)),)
	SDF_GZ_FILE := $(wildcard $(POSTSIM_PATH)/Cl1Top*.sdf.gz)
    SDF_OUT_FILE := $(POSTSIM_PATH)/Cl1Top.sdf
    _AUTO_UNZIP_SDF := $(shell \
        if [ -f $(SDF_GZ_FILE) ]; then \
            if [ ! -f $(SDF_OUT_FILE) ] || [ $(SDF_GZ_FILE) -nt $(SDF_OUT_FILE) ]; then \
                echo "Wait... Decompressing SDF: $(SDF_GZ_FILE) -> $(SDF_OUT_FILE)"; \
                gzip -cd $(SDF_GZ_FILE) > $(SDF_OUT_FILE); \
            else \
                echo "SDF file is up-to-date: $(SDF_OUT_FILE)"; \
            fi; \
        else \
            echo "Warning: SDF .gz file not found at $(SDF_GZ_FILE)"; \
        fi \
    )
endif

$(shell mkdir -p $(BUILD_DIR))
$(shell mkdir -p $(WAVE_DIR))
$(shell mkdir -p $(SAIF_DIR))

# Tools
MILL      := $(or $(shell which mill), ./mill) # Use global mill if available, otherwise use local ./mill
MKDIR     := mkdir -p
RM        := rm -rf
MAKE      ?= make
VCC       ?= vcs
WAVE      ?= gtkwave

# Phony Targets
.PHONY: all verilog help reformat checkformat clean run

# Generate Verilog
FIRTOOL_VERSION = 1.105.0
FIRTOOL_PATCH_DIR = $(shell pwd)/patch/firtool

verilog:
	@echo "Generating Verilog files..."
	$(MKDIR) $(VSRC_DIR)
	@./patch/update-firtool.sh $(FIRTOOL_VERSION) $(FIRTOOL_PATCH_DIR)
	CHISEL_FIRTOOL_PATH=$(FIRTOOL_PATCH_DIR)/firtool-$(FIRTOOL_VERSION)/bin \
	$(MILL) -i $(PRJ).runMain Elaborate --target-dir $(VSRC_DIR) --throw-on-first-error
	sed -i '/difftest\.sv/d' $(VSRC_DIR)/$(CPUTOP).sv
	sed -i '/Stat\.v/d' $(VSRC_DIR)/$(CPUTOP).sv
	
# Show Help for Elaborate
help:
	@echo "Displaying help for Elaborate..."
	$(MILL) -i $(PRJ).runMain Elaborate --help

# Reformat Code
reformat:
	@echo "Reformatting code..."
	$(MILL) -i __.reformat

# Check Code Format
checkformat:
	@echo "Checking code format..."
	$(MILL) -i __.checkFormat

# Clean Build Artifacts
clean:
	@echo "Cleaning build artifacts..."
	$(RM) $(BUILD_DIR)
	$(RM) $(wildcard $(VSRC_DIR)/*.sv $(VSRC_DIR)/*.v)


RTLSRC_CPU := $(wildcard $(CPU_DIR)/*.sv) $(wildcard $(CPU_DIR)/*.v) 

.PHONY: $(RTLSRC_CPU)

-include ./soc/soc.mk

ifeq ($(CONFIG_DBG), y)
 	-include ./riscv_dbg/dbg.mk
endif


riscv_dbg/remote_bitbang/librbs_veri.so: INCLUDE_DIRS =./
riscv_dbg/remote_bitbang/librbs_veri.so:
	$(MAKE) -C riscv_dbg/remote_bitbang all
	mv riscv_dbg/remote_bitbang/librbs.so $@

riscv_dbg/remote_bitbang/librbs_vcs.so:
riscv_dbg/remote_bitbang/librbs_vcs.so:
	$(MAKE) -C riscv_dbg/remote_bitbang all INCLUDE_DIRS="./ $(if $(VCS_HOME),$(VCS_HOME)/include,)"
	mv riscv_dbg/remote_bitbang/librbs.so $@

ifeq ($(CONFIG_DBG), y)
  ifeq ($(VCC), verilator)
    BITBANG_SO := $(abspath riscv_dbg/remote_bitbang/librbs_veri.so)
  else ifeq ($(VCC), vcs)
    BITBANG_SO := $(abspath riscv_dbg/remote_bitbang/librbs_vcs.so)
  endif
  TEST_CASE = while_loop
  TEST_NAME = loop
  VF += -DRISCV_DEBUG
  VCS_OUT = log/vcs_run.log
  OPENOCD_OUT = log/openocd_run.log
  OPENOCD_CFG = riscv_dbg/openocd_cfg/dm_compliance_test.cfg
endif



VTOP := top
COMPILE_OUT := $(BUILD_DIR)/compile.log
BIN := $(BUILD_DIR)/$(VTOP)

#./cl1/src/cc/verilator/difftest.cpp 

SDF_FLAGS := -sdf Max:top.soc_top_u.Cl1Top_u:$(SDF_OUT_FILE) \
			       +delay_mode_path \
                   +sdfverbose \
                   +neg_tchk \
                   -negdelay \
                   -diag=sdf:verbose \
                   +warn=OPD:10,IWNF:10,SDFCOM_UHICD:10,SDFCOM_ANICD:10,SDFCOM_NICD:10,DRTZ:10,SDFCOM_UHICD:10,SDFCOM_NTCDTL:10 

ifeq ($(VCC), verilator)
	VF += $(addprefix +incdir+, $(RTLSRC_INCDIR)) \
	--Wno-lint --Wno-UNOPTFLAT --Wno-BLKANDNBLK --Wno-COMBDLY --Wno-MODDUP \
	-CFLAGS -I$(abspath ./cl1/src/cc/verilator/include) \
	--timescale 1ns/1ps \
	--autoflush \
	--x-assign unique \
	--trace --trace-fst \
	--build -j 0 --exe --timing --binary\
	--Mdir $(BUILD_DIR) \
	--top-module $(VTOP) -o $(VTOP)
else ifeq ($(VCC), vcs)
	VF += $(addprefix +incdir+, $(RTLSRC_INCDIR)) \
	+vc -full64 -sverilog +v2k -timescale=1ns/1ps \
	-LDFLAGS -Wl,--no-as-needed \
	$(if $(and $(strip $(POSTSIM)), $(strip $(SDF_ON))), $(SDF_FLAGS) , +notimingcheck +nospecify) \
	+lint=TFIPC-L \
	-lca -kdb \
	-CC "$(if $(VCS_HOME), -I$(VCS_HOME)//include,)" \
	-debug_access -l $(COMPILE_OUT) \
	-Mdir=$(BUILD_DIR) \
	-top $(VTOP) -o $(BUILD_DIR)/$(VTOP)
else 
	$(error unsupport VCC)
endif

#	-xprop=xprop.cfg 

$(BIN): $(RTLSRC_CPU) $(RTLSRC_DBG) $(RTLSRC_SOC) $(BITBANG_SO)
	$(VCC) $(RTLSRC_CPU) $(RTLSRC_DBG) $(BITBANG_SO) $(RTLSRC_SOC) $(VF)

bin: $(BIN)

REF ?= ./utils/riscv32-spike-so
TEST_CASE ?= dummy
TEST_NAME ?= dummy

ifeq ($(VCC), verilator) 
	WAVE_TYPE = fst
else ifeq ($(VCC), vcs)
	WAVE_TYPE = fsdb
endif
#WAVE_TYPE ?= fst
POWER_RTL_F  = +rtl_saif +design_name=$(TEST_NAME)
POWER_GATE_F = +gate_saif +design_name=$(TEST_NAME)
POWER_FLAGS  = $(if $(PWR_ANALYSIS), \
			   $(if $(POSTSIM), $(POWER_GATE_F), $(POWER_RTL_F)),)

CURRENT_TIME := $(shell date +%s)

ifneq ($(DUMP_WAVE),)
RUN_ARGS += +$(WAVE_TYPE)
endif
RUN_ARGS += --diff
RUN_ARGS += +firmware=./test/$(TEST_CASE)/build/$(TEST_NAME).hex
RUN_ARGS += --image=./test/$(TEST_CASE)/build/$(TEST_NAME).bin
RUN_ARGS += --ref=$(REF)
# RUN_ARGS += +verilator+rand+reset+2 +verilator+seed+$(CURRENT_TIME)
RUN_ARGS += $(POWER_FLAGS)

# RUN_ARGS += +vcs+initreg+0

# Test Targets (run, gdb, latest)
run: $(BIN)
	$(BIN) $(RUN_ARGS)

dbg_test: $(BIN)
	@mkdir -p log
	@echo "[INFO] Starting dbg_test simulation..."
	@(  \
	    echo "[CMD] $(BIN) $(RUN_ARGS) +verbose &> $(VCS_OUT) &"; \
		VCS_PID=""; \
		cleanup() { \
			echo "[INFO] Cleaning up..."; \
			[ -n "$$VCS_PID" ] && kill $$VCS_PID 2>/dev/null; \
			exit 1; \
		}; \
		trap 'echo; cleanup' INT TERM; \
		./$(BIN) $(RUN_ARGS)  +verbose &> $(VCS_OUT) & \
		VCS_PID=$$!; \
		echo "[INFO] VCS PID: $$VCS_PID"; \
		until grep -q "Listening on port" $(VCS_OUT); do sleep 1; done; \
		echo "[INFO] Detected 'Listening on port', launching OpenOCD..."; \
		./tools/openocd -f $(OPENOCD_CFG) |& tee $(OPENOCD_OUT); \
		wait $$VCS_PID; \
	)
	@echo "[INFO] Simulation completed."


#TODO: add test submodule
test: bin
	@for dir in $(CL2_TEST_DIR); do \
		$(MAKE) -C $$dir run ARCH=riscv32-cl2 || exit 1; \
	done


wave:
	$(WAVE) $(WAVE_DIR)/$(VTOP).$(WAVE_TYPE)

verdi:
	verdi -ssf $(WAVE_DIR)/$(VTOP).fsdb &

$(WAVE_DIR)/$(VTOP).saif : $(WAVE_DIR)/$(VTOP).fsdb
	@echo "Generating SAIF file from FSDB..."
	fsdb2saif $^ -s "/top/soc_top_u/Cl1Top_u" -o $@

saif: $(WAVE_DIR)/$(VTOP).saif

.PHONY: $(BIN) wave saif
