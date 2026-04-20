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
| `simpleSocTest` | `true` | 选择 simple SoC 测试场景，影响取指/访存地址映射判断。 |
| `fullSocTest` | `false` | 选择 full SoC 场景；开启后会同时影响启动地址、差分接口和 AXI 位宽。 |

说明：源码中原本希望 `syn`、`simpleSocTest`、`fullSocTest` 三者“有且仅有一个为 `true`”，但当前 `require` 检查被注释掉了，因此仓库当前状态是 `syn=true` 且 `simpleSocTest=true`。

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
| `HAS_ICACHE` | `false` | 是否实例化 ICache；关闭时 IF 侧通过桥接模块直接接入总线。 |
| `HAS_DCACHE` | `false` | 是否实例化 DCache；关闭时 LSU 通过桥接模块直接接入总线。 |
| `RST_ACTIVELOW` | `true` | 顶层复位信号低有效。 |
| `RST_ASYNC` | `true` | 顶层内部使用异步复位。 |
| `SOC_DIFF` | `false` | 是否导出 SoC 差分测试端口 `diff_o`；当 `fullSocTest=true` 时为 `true`。 |
| `SramFoundary` | `true` | 是否使用工艺 SRAM 宏；当前由 `syn || fullSocTest` 派生。 |
| `SOC_D64` | `false` | 顶层 AXI 数据位宽是否扩展为 64 位；仅在 `fullSocTest=true` 且未暴露 `CoreBus` 时生效。 |
| `Technology` | `"SMIC110"` | SRAM 宏选择使用的工艺标识，当前 `utils/SRAM.scala` 里用于选择具体 SRAM 实现。 |
| `FORMAL_VERIF` | `true` | 开启形式验证相关逻辑，顶层会导出 `RVFI` 接口，MDU 也会切换到 formal 友好的结果生成方式。 |
| `EXPOSE_CORE_BUS` | `true` | 顶层直接暴露 `ibus`/`dbus` 两个 `CoreBus` 接口；为 `false` 时改为导出 AXI4 `master` 接口，并接入 cache/xbar/桥接逻辑。 |

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

生成的 Verilog 文件位于 `vsrc/Cl1Top.sv`。
