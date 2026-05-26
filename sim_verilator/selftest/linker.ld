ENTRY(_start)

MEMORY
{
  RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 16M
}

SECTIONS
{
  . = ORIGIN(RAM);

  .text : ALIGN(4)
  {
    KEEP(*(.text.init))
    *(.text .text.*)
    *(.rodata .rodata.*)
  } > RAM

  .tohost : ALIGN(4)
  {
    KEEP(*(.tohost))
  } > RAM

  .fromhost : ALIGN(4)
  {
    KEEP(*(.fromhost))
  } > RAM

  .data : ALIGN(4)
  {
    *(.data .data.*)
    *(.sdata .sdata.*)
  } > RAM

  .bss (NOLOAD) : ALIGN(4)
  {
    __bss_start = .;
    *(.sbss .sbss.*)
    *(.bss .bss.*)
    *(COMMON)
    __bss_end = .;
  } > RAM

  . = ALIGN(16);
  . += 4096;
  _stack_top = .;

  /DISCARD/ :
  {
    *(.comment)
    *(.eh_frame*)
    *(.riscv.attributes)
    *(.debug*)
  }
}
