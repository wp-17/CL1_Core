#ifndef __DIFFTEST_DEF_H__
#define __DIFFTEST_DEF_H__
#include <stdint.h>

#define __EXPORT __attribute__((visibility("default")))
enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

typedef uint32_t word_t   ;
typedef int32_t  sword_t  ;
typedef uint32_t paddr_t  ;

#define EXTRAM_BASE 0x80000000
#define EXTRAM_SIZE 64 * 1024 * 1024
#define ITCM_BASE   0x90000000
#define ITCM_SIZE   0x00010000
#define DTCM_BASE   0xA0000000
#define DTCM_SIZE   0x00010000

#endif