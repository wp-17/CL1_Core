# Cl1 Core

Cl1 是一个使用 Chisel 开发的 32 位 RISC-V 处理器核。

## 处理器特性

| 特性 | 描述 |
|------|------|
| 架构 | RV32IMC_Zicsr |
| 指令集 | I (基础整数) + M (乘除法) + C (压缩指令) + Zicsr (CSR 操作) |
| 流水线 | 多级流水线，支持可选的 WB 流水级 |
| 缓存 | 可选的 ICache 和 DCache |
| 总线 | AXI4 接口 |
| 调试 | 支持 RISC-V Debug Module |
| 中断 | 支持外部中断、软件中断、定时器中断 |
| 特权模式 | Machine Mode |

## 目录结构

```
cl1_core/
├── cl1/
│   └── src/
│       ├── scala/          # 核心 Chisel 源码
│       │   ├── Cl1Config.scala    # 配置对象
│       │   ├── Cl1Core.scala      # 核心顶层
│       │   ├── Cl1Top.scala       # 顶层封装
│       │   ├── Cl1IFStage.scala   # 取指阶段
│       │   ├── Cl1IDEXStage.scala # 译码执行阶段
│       │   ├── Cl1WBStage.scala   # 写回阶段
│       │   ├── Cl1ALU.scala       # 算术逻辑单元
│       │   ├── Cl1MDU.scala       # 乘除单元
│       │   ├── Cl1LSU.scala       # 访存单元
│       │   ├── Cl1CSR.scala       # CSR 寄存器
│       │   ├── Cl1ICACHE.scala    # 指令缓存
│       │   ├── Cl1DCACHE.scala    # 数据缓存
│       │   ├── Cl1BPU.scala       # 分支预测单元
│       │   └── ...
│       └── utils/          # 工具模块
├── vsrc/                   # 生成的 Verilog 输出
├── wave/                   # 仿真波形文件
├── patch/                  # firtool 补丁
├── build.sc                # Mill 构建配置
├── Makefile                # 构建脚本
└── flake.nix               # Nix flake 配置
```

## 配置说明

配置集中定义在 `cl1/src/scala/Cl1Config.scala`。下面的“默认值”以当前仓库中的源码为准，部分选项是由其他开关派生出来的。

### globalConfig

`globalConfig` 用于选择生成目标/测试场景。

| 配置项 | 当前值 | 描述 |
|--------|--------|------|
| `syn` | `true` | 综合相关配置开关，会影响 `SramFoundary` 等派生选项。 |
| `simpleSocTest` | `true` | 由 `CL1_PLATFORM=simple_soc` 派生，选择 simple SoC 测试场景。 |
| `fullSocTest` | `false` | 由 `CL1_PLATFORM=full_soc` 派生；开启后会同时影响启动地址、差分接口和 AXI 位宽。 |

说明：`simpleSocTest` 和 `fullSocTest` 不再手工独立配置，统一由 `CL1_PLATFORM` 选择。

### Cl1Config

| 配置项 | 当前值 | 描述 |
|--------|--------|------|
| `BOOT_ADDR` | `0x80000000` | 复位后 PC 启动地址；当 `fullSocTest=true` 时切换为 `0x01000000`。 |
| `TVEC_ADDR` | `0x20000000` | `mtvec` 初始异常向量基地址。 |
| `BUS_WIDTH` | `32` | 核内 AXI/CoreBus 基础数据宽度。 |
| `CKG_EN` | `false` | 顶层核心时钟门控开关；打开后在 `WFI` 状态下可门控核心时钟。 |
| `difftest` | `false` | Difftest 相关逻辑开关；当前代码写法下恒为 `false`。 |
| `DBG_ENTRYADDR` | `0x800` | Debug Mode 入口地址。 |
| `DBG_EXCP_BASE` | `0x800` | Debug 异常入口基地址。 |
| `MDU_SHAERALU` | `false` | MDU 是否与 ALU 共享部分数据通路。 |
| `WB_PIPESTAGE` | `true` | 是否保留独立 WB 级；关闭后 ID/EX 直接连到 WB。 |
| `HAS_ICACHE` | `false` in `bus`, `true` in `cache` | 是否实例化 ICache；由 `CL1_TEST_MODE` 默认选择。 |
| `HAS_DCACHE` | `false` in `bus`, `true` in `cache` | 是否实例化 DCache；由 `CL1_TEST_MODE` 默认选择。 |
| `RST_ACTIVELOW` | `true` | 顶层复位信号低有效。 |
| `RST_ASYNC` | `true` | 顶层内部使用异步复位。 |
| `SOC_DIFF` | `false` | 是否导出 SoC 差分测试端口 `diff_o`；当 `fullSocTest=true` 时为 `true`。 |
| `SramFoundary` | `true` in `bus`, `false` in `cache` | 是否使用工艺 SRAM 宏；cache 仿真默认使用 `SyncReadMem`，避免 Verilator 依赖工艺 SRAM 黑盒。 |
| `SOC_D64` | `false` | 顶层 AXI 数据位宽是否扩展为 64 位；仅在 `fullSocTest=true` 且未暴露 `CoreBus` 时生效。 |
| `Technology` | `"SMIC110"` | SRAM 宏选择使用的工艺标识，当前 `utils/SRAM.scala` 里用于选择具体 SRAM 实现。 |
| `FORMAL_VERIF` | `true` | 开启形式验证相关逻辑，顶层会导出 `RVFI` 接口，MDU 也会切换到 formal 友好的结果生成方式。 |
| `EXPOSE_CORE_BUS` | `true` in `bus`, `false` in `cache` | 顶层直接暴露 `ibus`/`dbus` 两个 `CoreBus` 接口；cache 模式导出 AXI4 `master` 接口，并接入 ICache/DCache/xbar/桥接逻辑。 |

地址空间配置在 `cl1/src/scala/AddressMap.scala` 和 `sim_verilator/platforms.py` 中显式维护。默认平台是 `simple_soc`；如需切换到 full SoC，使用 `CL1_PLATFORM=full_soc` 重新构建。`CL1_ADDRESS_PROFILE` 仍作为旧脚本兼容别名保留。

### 测试模式切换

`CL1_TEST_MODE=bus` 是直通 CoreBus 测试模式；`CL1_TEST_MODE=cache` 是 ICache/DCache + AXI 测试模式。

```bash
CL1_TEST_MODE=bus ./sim_verilator/build.sh
CL1_TEST_MODE=cache ./sim_verilator/build.sh
CL1_PLATFORM=full_soc ./sim_verilator/build.sh
./sim_verilator/regression.py --test-mode cache --suite full
./sim_verilator/run_riscv_dv.py --test-mode cache --compare /home/dgy1/prjs/riscv-dv/verification_output/rv32imc_mmode_directed_suite
./sim_verilator/cl1_sim.py check --level full
./sim_verilator/cl1_sim.py check --level harness
./sim_verilator/cl1_sim.py check --level all
```

生成/构建产物按模式分离在 `vsrc/bus`、`vsrc/cache`、`sim_verilator/build/bus`、`sim_verilator/build/cache`。

### Cl1PowerSaveConfig

| 配置项 | 当前值 | 描述 |
|--------|--------|------|
| `MODPOWERCFG` | `false` | 模块级低功耗总开关。 |
| `MDU_CKG_EN` | `false` | MDU 时钟门控开关；由 `MODPOWERCFG` 派生。 |
| `DCACHE_CKG_EN` | `false` | DCache 时钟门控开关；由 `MODPOWERCFG` 派生。 |
| `LSU_CKG_EN` | `false` | LSU 时钟门控开关；由 `MODPOWERCFG` 派生。 |
| `RF_NORESET` | `true` | 通用寄存器堆上电后不做显式复位初始化。 |

## 构建与生成 Verilog

### 环境要求

- Mill 构建工具
- Scala 2.13.15
- Chisel 6.6.0
- firtool 1.105.0 (会自动下载)

### 生成 Verilog

```bash
make verilog
```

生成的 Verilog 文件位于 `vsrc/<mode>/Cl1Top.sv`，其中 `<mode>` 为 `bus` 或 `cache`。
