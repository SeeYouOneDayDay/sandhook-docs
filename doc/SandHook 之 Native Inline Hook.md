# SandHook 之 Native Inline Hook

# 简介



在 SandHook ART Hook 稳定之后，抽空把 [Native](https://so.csdn.net/so/search?q=Native&spm=1001.2101.3001.7020) Inline Hook 实现了，虽然有重复造轮子的嫌疑，但确实是本人一行一行码出来的，基本所有的东西都是自己实现的。



[Github](https://so.csdn.net/so/search?q=Github&spm=1001.2101.3001.7020): https://github.com/ganyao114/SandHook



# 支持



目前支持



- ARM32
- ARM64



其实 X86 非常好实现，但是想了一下还是往后稍稍吧，等 [ARM](https://so.csdn.net/so/search?q=ARM&spm=1001.2101.3001.7020) 稳定了再说



# 特点



- 纯手写的反汇编器和汇编器



没有用 vixl 或者其他库，也不是那种一堆 bit 操作难以阅读那种，可读性很强。
当然我也仅仅实现了部分常见指令。



- 指令修复考虑到了更多的 Case



比如当你重复多次 Hook 同一个函数时，SandHook 可以比较好的支持



- 纯手写的 ELF 解析



你可以搜索到比 dlysm 更多的符号，你可以直接这样 hook



```
suspendVMBackup = reinterpret_cast<void (*)()>(SandInlineHookSym("/system/lib/libart.so", "_ZN3art3Dbg9SuspendVMEv",                                                                 reinterpret_cast<void *>(SuspendVMReplace)));
```



- 除了 Hook 之外



你也可以用其中的汇编器和解析器做一些其他事情，当然也可以很方便的扩展支持更多的指令。



# 代码



## Hook 部分



```
#include "hook_arm64.h"
#include "code_buffer.h"
#include "lock.h"

using namespace SandHook::Hook;
using namespace SandHook::Decoder;
using namespace SandHook::Asm;
using namespace SandHook::Assembler;
using namespace SandHook::Utils;

#include "assembler_arm64.h"
#include "code_relocate_arm64.h"
using namespace SandHook::RegistersA64;
void *InlineHookArm64Android::inlineHook(void *origin, void *replace) {
    AutoLock lock(hookLock);

    void* backup = nullptr;
    AssemblerA64 assemblerBackup(backupBuffer);

    StaticCodeBuffer inlineBuffer = StaticCodeBuffer(reinterpret_cast<Addr>(origin));
    AssemblerA64 assemblerInline(&inlineBuffer);
    CodeContainer* codeContainerInline = &assemblerInline.codeContainer;

    //build inline trampoline
#define __ assemblerInline.
    Label* target_addr_label = new Label();
    __ Ldr(IP1, target_addr_label);
    __ Br(IP1);
    __ Emit(target_addr_label);
    __ Emit((Addr) replace);
#undef __

    //build backup method
    CodeRelocateA64 relocate = CodeRelocateA64(assemblerBackup);
    backup = relocate.relocate(origin, codeContainerInline->size(), nullptr);
#define __ assemblerBackup.
    Label* origin_addr_label = new Label();
    __ Ldr(IP1, origin_addr_label);
    __ Br(IP1);
    __ Emit(origin_addr_label);
    __ Emit((Addr) origin + codeContainerInline->size());
    __ finish();
#undef __

    //commit inline trampoline
    assemblerInline.finish();
    return backup;
}
```



## 指令解析与汇编



首先是描述一个指令的 bit 结构，基本可以对照 ARM 手册



```
DEFINE_OPCODE_T32(LDR_LIT, 0b1111100)
DEFINE_STRUCT_T32(LDR_LIT) {
    InstT32 op:7;
    InstT32 U:1;
    InstT32 S:1;
    InstT32 opcode:7;
    InstT32 imm12:12;
    InstT32 rt:T32_REG_WIDE;
};
```



指令的解析与汇编



```
void T32_LDR_LIT::decode(T32_STRUCT_LDR_LIT *inst) {
    DECODE_OP;
    DECODE_RT(Reg);
    s = S(inst->S);
    offset = getImmPCOffset();
}

void T32_LDR_LIT::assembler() {
    SET_OPCODE(LDR_LIT);
    ENCODE_OP;
    ENCODE_RT;
    get()->S = s;
    if (offset >= 0) {
        get()->U = add;
        get()->imm12 = static_cast<InstT32>(offset);
    } else {
        get()->U = cmp;
        get()->imm12 = static_cast<InstT32>(-offset);
    }
}
```



汇编器：基本是对每个指令封装的调用



```
void AssemblerA32::Mov(RegisterA32 &rd, U16 imm16) {
    Emit(reinterpret_cast<Unit<Base>*>(new INST_T32(MOV_MOVT_IMM)(INST_T32(MOV_MOVT_IMM)::MOV, rd, imm16)));
}

void AssemblerA32::Movt(RegisterA32 &rd, U16 imm16) {
    Emit(reinterpret_cast<Unit<Base>*>(new INST_T32(MOV_MOVT_IMM)(INST_T32(MOV_MOVT_IMM)::MOVT, rd, imm16)));
}

void AssemblerA32::Mov(RegisterA32 &rd, U32 imm32) {
    U16 immL = BITS16L(imm32);
    U16 immH = BITS16H(imm32);
    Mov(rd, immL);
    Movt(rd, immH);
}
```



解析器：



```
void Arm64Decoder::decode(void *codeStart, Addr codeLen, InstVisitor &visitor, bool onlyPcRelInst) {
    InstA64 *pc = reinterpret_cast<InstA64 *>(codeStart);
    Addr endAddr = (Addr) codeStart + codeLen;
    Unit<Base>* unit = nullptr;
    while((Addr) pc < endAddr) {
        // pc relate insts
        CASE(B_BL)
        CASE(B_COND)
        CASE(CBZ_CBNZ)
        CASE(TBZ_TBNZ)
        CASE(LDR_LIT)
        CASE(ADR_ADRP)
        if (onlyPcRelInst)
            goto label_matched;
        CASE(MOV_WIDE)
        CASE(MOV_REG)
        CASE(LDR_IMM)
        CASE(LDR_UIMM)
        CASE(LDRSW_IMM)
        CASE(LDRSW_UIMM)
        CASE(STR_UIMM)
        CASE(STR_IMM)
        CASE(BR_BLR_RET)
        CASE(SUB_EXT_REG)
        CASE(SVC)
        CASE(EXCEPTION_GEN)
        label_matched:
        if (unit == nullptr) {
            unit = reinterpret_cast<Unit<Base> *>(new INST_A64(UNKNOW)(*reinterpret_cast<STRUCT_A64(UNKNOW) *>(pc)));
        }
        if (!visitor.visit(unit, pc)) {
            break;
        }
        pc = reinterpret_cast<InstA64 *>((Addr)pc + unit->size());
        unit = nullptr;
    }
}
```



指令修复



```
void* CodeRelocateA64::relocate(Instruction<Base> *instruction, void *toPc) throw(ErrorCodeException) {
    void* curPc = __ getPC();

    //insert later bind labels
    __ Emit(getLaterBindLabel(curOffset));

    if (!instruction->pcRelate()) {
        __ Emit(instruction);
        instruction->ref();
        return curPc;
    }
    switch (instruction->instCode()) {
        CASE(B_BL)
        CASE(B_COND)
        CASE(TBZ_TBNZ)
        CASE(CBZ_CBNZ)
        CASE(LDR_LIT)
        CASE(ADR_ADRP)
        default:
            __ Emit(instruction);
            instruction->ref();
    }
    return curPc;
}


IMPL_RELOCATE(B_BL) {

    if (inRelocateRange(inst->offset, sizeof(InstA64))) {
        inst->ref();
        inst->bindLabel(*getLaterBindLabel(inst->offset + curOffset));
        __ Emit(reinterpret_cast<Instruction<Base>*>(inst));
        return;
    }

    Addr targetAddr = inst->getImmPCOffsetTarget();

    if (inst->op == inst->BL) {
        Addr lr = reinterpret_cast<Addr>(toPc);
        lr += 4 * 4; // MovWide * 4;
        lr += 4 * 4; // MovWide * 4;
        lr += 4; // Br
        __ Mov(LR, lr);
    }
    __ Mov(IP1, targetAddr);
    __ Br(IP1);
}
```



# 结尾



其实 Native Inline Hook 与 ART Hook 还是有很大不同的，其本身并没有什么深度，只要你多看看手册 ==，当然 native hook 目前测试的不多，欢迎测试一起完善。