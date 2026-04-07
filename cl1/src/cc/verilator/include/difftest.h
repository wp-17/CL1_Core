#ifndef _DIFFTEST_H_
#define _DIFFTEST_H_

#include "Vtop.h"
#define RESET_VECTOR 0x80000000U
#define TRAP_VECTOR  0x20000000U
#define PMEM_SIZE 0x8000000U
#define GPR_NUM 32
#define CSR_NUM 16 //Don't modify this
#define __EXPORT __attribute__((visibility("default")))
#define DIFF_MEMCPY (void (*)(unsigned int, void *, size_t, bool))
#define DIFF_REGCPY (void (*)(void *, bool))
#define DIFF_EXEC (void (*)(uint64_t))
#define DIFF_INIT (void (*)(int))

#define COLOR_RED "\033[1;31m"
#define COLOR_END "\033[0m"

enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

typedef struct context {
  uint32_t gpr[GPR_NUM];
  uint32_t csr[CSR_NUM];
  uint32_t pc;
} core_context_t;

typedef struct _diff_info_t {
  uint32_t pc;
  uint32_t inst;

  uint16_t wen;
  uint16_t rdIdx;
  uint32_t wdata;

  uint16_t csr_wen;
  uint32_t csr_wdata;
  uint16_t csr_waddr;
} difftest_info_t;

#ifdef __cplusplus
extern "C" {
#endif

void difftest_init(const Vtop *p, const char *ref_so_file, const char *img_file);

#ifdef __cplusplus
}
#endif

#endif
