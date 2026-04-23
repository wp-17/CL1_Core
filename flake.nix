{
  description = "Scala and Mill development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    nur.url = "github:nix-community/NUR";
  };

  outputs = { self, nixpkgs, nur}:
    let
      # 选择一个系统（例如 x86_64-linux）
      system = "x86_64-linux";

      pythonWithPexpect = (pkgs.python310.withPackages (ps: [ 
        ps.pexpect 
        ps.pyaml
        ps.kconfiglib
  
      ])).overrideAttrs (oldAttrs: {
        pname = "pyhton310";
        version = "local-1.0";
      });

      # 2. Cross-compilation package set for the TARGET (RISC-V)
      pkgsRiscv = import nixpkgs {
        inherit system;
        crossSystem = {
          config = "riscv32-none-elf";
          gcc.abi = "ilp32";
        };
      };


      # 获取 nixpkgs 的实例
      pkgs = import nixpkgs { inherit system; 
        overlays = [nur.overlays.default];
        config  = {
          allowUnfree = true; # 如果需要使用非自由软件包
        };
      };


      openocd-riscv = pkgs.nur.repos.bendlas.openocd-riscv;

      # 定义开发环境
      devShell = pkgs.mkShell {
          buildInputs = [
            # === HOST Tools (run on your machine) ===
            pkgs.mill
            pkgs.verilator
            pkgs.gtkwave
            pkgs.zlib
            pkgs.spike
            pkgs.dtc
            pkgs.espresso
            pythonWithPexpect
            pkgs.ccache
            openocd-riscv
            pkgs.python3Packages.kconfiglib
            pkgs.scons

            # === TARGET Toolchain (builds code for RISC-V) ===
            # Use the toolchain you defined in pkgsRiscv
            # pkgs.pkgsCross.riscv64-embedded.buildPackages.gcc11
            # pkgs.pkgsCross.riscv32-embedded.buildPackages.gcc
            # pkgs.pkgsCross.riscv32-embedded.buildPackages.gcc
            pkgsRiscv.buildPackages.gcc
            pkgsRiscv.buildPackages.gdb
          ];

        # 设置环境变量（可选）
        shellHook = ''
          export OBJCACHE=ccache
          echo "Welcome to the digtal sim development environment!"
          exec zsh
        '';
      };
    in
    {
      # 导出开发环境
      devShells."${system}".default = devShell;
      packages."${system}"= {
        openocd-riscv = openocd-riscv;
        python3       = pythonWithPexpect;
      };
    };
}