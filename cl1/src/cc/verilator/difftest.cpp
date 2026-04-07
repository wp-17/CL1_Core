#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <Vtop.h>
#include <difftest.h>
#include <svdpi.h>
// #include "Vtop__Dpi.h"

#ifdef __cplusplus
extern "C" {
#endif


static core_context_t dut;
static core_context_t ref;

static void (*ref_difftest_memcpy)(unsigned int addr, void *buf, size_t n,
                            bool direction) = NULL;
static void (*ref_difftest_regcpy)(void *dut, bool direction) = NULL;
static void (*ref_difftest_exec)(uint64_t n) = NULL;

static uint8_t mem[PMEM_SIZE];

static unsigned int load_img(const char *path) {
  assert(mem);
  assert(path);
  FILE *img = fopen(path, "rb");
  assert(img);

  fseek(img, 0, SEEK_END);
  unsigned int size = ftell(img);
  if (size > PMEM_SIZE) {
    fclose(img);
    return 0;
  }
  fseek(img, 0, SEEK_SET);
  int ret = fread(mem, size, 1, img);
  if (ret != 1) {
    fclose(img);
    return 0;
  }

  fclose(img);
  return size;
}

void difftest_init(const Vtop *p, const char *ref_so_file, const char *img_file) {
  printf("[DIFFTEST] init ...\n");

  // Initialize all difftest function pointers
  void *handle;
  handle = dlopen(ref_so_file, RTLD_LAZY);
  assert(handle);
  ref_difftest_memcpy = DIFF_MEMCPY dlsym(handle, "difftest_memcpy");
  assert(ref_difftest_memcpy);
  ref_difftest_regcpy = DIFF_REGCPY dlsym(handle, "difftest_regcpy");
  assert(ref_difftest_regcpy);
  ref_difftest_exec = DIFF_EXEC dlsym(handle, "difftest_exec");
  void (*ref_difftest_init)(int) = DIFF_INIT dlsym(handle, "difftest_init");
  assert(ref_difftest_init);
  ref_difftest_init(80); //Don't care the port

  // Load image file and copy to REF
  size_t size = load_img(img_file);
  ref_difftest_memcpy(RESET_VECTOR, mem, size, DIFFTEST_TO_REF);

  // Initialize DUT and local REF state
  dut.pc = RESET_VECTOR;
  ref.pc = RESET_VECTOR;
  ref.csr[2] = TRAP_VECTOR;

  // Initialize remote REF state
  ref_difftest_regcpy((void *)&ref, DIFFTEST_TO_REF);
  printf("[DIFFTEST] finish initialization, image size : %ld\n", size);
}

void update_dut_state(int idx, unsigned int wdata) { 
  dut.gpr[idx] = wdata;
  dut.gpr[0]   = 0;
}

void difftest_info(bool pc_flag, int gpr_mask, int csr_mask) {
  printf("[DIFFTEST] DUT's PC is %0#8x. \n", dut.pc);
  if(pc_flag)
    printf("[DIFFTEST]" COLOR_RED " DUT's PC is different from REF's PC, REF's PC is %0#8x.\n" COLOR_END, ref.pc);

  if(gpr_mask) {
    for(int i = 0; i < GPR_NUM; i++) {
      if((gpr_mask >> i) & 1)
        printf("[DIFFTEST]" COLOR_RED " DUT's GPR[%d] is different from REF's. The value is %0#8x, "
            "which should be %0#8x. \n" COLOR_END, i, dut.gpr[i], ref.gpr[i]);
    }
  }

  //TODO: add CSRs and use map
  if(csr_mask) {
    for(int i = 0; i < 3; i++) {
      if((csr_mask >> i) & 1)
        printf("[DIFFTEST]" COLOR_RED " DUT's CSR[%d] is different from REF's. The value is %0#8x, "
            "which should be %0#8x. \n" COLOR_END, i, dut.csr[i], ref.csr[i]);
    }
  }

}

int difftest_step(int n, svOpenArrayHandle info) {

  int err_flag = 0;
  difftest_info_t *diff_info_ptr = (difftest_info_t *)svGetArrayPtr(info);
  static uint32_t pre_pc = RESET_VECTOR;
  if( n == 1 ) {
    ref_difftest_exec(1);
  }
  ref_difftest_regcpy((void *)&ref, DIFFTEST_TO_DUT);
  if(diff_info_ptr->pc != pre_pc) {
    err_flag |= 0x1;
  }
  if(diff_info_ptr->wen == 1) {
    if(ref.gpr[diff_info_ptr->rdIdx] != diff_info_ptr->wdata) {
      err_flag |= 0x2;
    }
  }

  if(err_flag != 0) {
    if(err_flag & 0x1) {
      printf("[DIFFTEST]" COLOR_RED " PC wrong at PC = %0#8x: DUT's PC is %0#8x, REF's PC is %0#8x.\n" COLOR_END,
           pre_pc, dut.pc, ref.pc);
    }
    if(err_flag & 0x2) {
      printf("[DIFFTEST]" COLOR_RED " GPR[%d] wrong at PC = %0#8x: DUT's GPR is %0#8x, REF's GPR is %0#8x.\n" COLOR_END,
           diff_info_ptr->rdIdx, pre_pc, diff_info_ptr->wdata, ref.gpr[diff_info_ptr->rdIdx]);
    }
  }

  pre_pc = ref.pc;
  return err_flag;
}


#ifdef __cplusplus
}
#endif
