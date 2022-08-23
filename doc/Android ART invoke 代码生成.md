# Android ART invoke 代码生成

# 前言



在前面 SandHook 系列我们知道 ArtMethod 入口替换并不能覆盖所有的方法，而且这个问题比预想的严重的多的多。
而导致 Hook 不到的原因不仅仅是 inline 优化，在 Android O 之前 Inline 只是小头，真正主要的原因是 Art Optimizing [代码生成](https://so.csdn.net/so/search?q=代码生成&spm=1001.2101.3001.7020)的 Sharpening 优化。



# Quick & Optimizing



ART 中的 Compiler 有两种



- Quick
- Optimizing



Quick 在 4.4 就引入，直到 6.0 一直作为默认 Compiler, 直到 7.0 被移除。



Optimizing 5.0 引入，7.0 - 9.0 作为唯一 Compiler。



下面以 Optimizing Compiler 为例分析 ART 方法调用的生成。



# Optimizing



Optimizing 比 Quick 生成速度慢，但是会附带各种优化，包括：



- 逃逸分析：如果不能逃逸，则直接栈上分配
- 常量折叠
- 死代码块移除
- 方法内联
- 指令精简
- 指令重拍序
- load/store 精简
- Intrinsic 函数替换



。。。



其中包括 Invoke 代码生成：



**invoke-static/invoke-direct 代码生成默认使用 Sharpening 优化**。



# Sharpening



Sharpening 做了两件事情：



- 确定加载 ArtMethod 的方式和位置
- 确定直接 blr 入口调用方法还是查询 ArtMethod -> CodeEntry 调用方法



结果保存在 MethodLoadKind & CodePtrLocation 两个 enum 中



- MethodLoadKind 就是 ArtMethod 加载类型
- CodePtrLocation 就是跳转地址的类型



我们重点关注 CodePtrLocation：
但是 CodePtrLocation 在 8.0 有重大变化：



## 8.0 之前



```
 // Determines the location of the code pointer.
  enum class CodePtrLocation {
    // Recursive call, use local PC-relative call instruction.
    kCallSelf,

    // Use PC-relative call instruction patched at link time.
    // Used for calls within an oat file, boot->boot or app->app.
    kCallPCRelative,

    // Call to a known target address, embed the direct address in code.
    // Used for app->boot call with non-relocatable image and for JIT-compiled calls.
    kCallDirect,

    // Call to a target address that will be known at link time, embed the direct
    // address in code. If the image is relocatable, emit .patch_oat entry.
    // Used for app->boot calls with relocatable image and boot->boot calls, whether
    // the image relocatable or not.
    kCallDirectWithFixup,

    // Use code pointer from the ArtMethod*.
    // Used when we don't know the target code. This is also the last-resort-kind used when
    // other kinds are unimplemented or impractical (i.e. slow) on a particular architecture.
    kCallArtMethod,
  };
```



- kCallSelf 顾名思义，递归调用自己，此时不需要重新加载 ArtMethod，可以直接确定代码位置。
- kCallPCRelative，直接 B 到下面的方法，多见于调用附近的方法。
- kCallDirect ，可以直接知道编译完成的入口代码，则可以跳过 ArtMethod->CodeEntry 查询，直接 blx entry。多见于调用系统方法，这些方法中都是绝对地址，不需要重定向。
- kCallDirectWithFixup，link OAT 文件的时候，才能确定方法在内存中的位置，方法入口需要 linker 重定向。也不需要查询 ArtMethod。
- kCallArtMethod，此种需要在 Runtime 期间得知方法入口，需要查询 ArtMethod->CodeEntry。那么由此可见只有在此种情况下，入口替换的 Hook 才有可能生效。



### 代码生成



```
void CodeGeneratorARM64::GenerateStaticOrDirectCall(HInvokeStaticOrDirect* invoke, Location temp) {


//处理 ArtMethod 加载位置
...........

//生成跳转代码
switch (invoke->GetCodePtrLocation()) {
    case HInvokeStaticOrDirect::CodePtrLocation::kCallSelf:
      __ Bl(&frame_entry_label_);
      break;
    case HInvokeStaticOrDirect::CodePtrLocation::kCallPCRelative: {
      relative_call_patches_.emplace_back(invoke->GetTargetMethod());
      vixl::Label* label = &relative_call_patches_.back().label;
      vixl::SingleEmissionCheckScope guard(GetVIXLAssembler());
      __ Bind(label);
      __ bl(0);  // Branch and link to itself. This will be overriden at link time.
      break;
    }
    case HInvokeStaticOrDirect::CodePtrLocation::kCallDirectWithFixup:
    case HInvokeStaticOrDirect::CodePtrLocation::kCallDirect:
      // LR prepared above for better instruction scheduling.
      DCHECK(direct_code_loaded);
      // lr()
      __ Blr(lr);
      break;
    case HInvokeStaticOrDirect::CodePtrLocation::kCallArtMethod:
      // LR = callee_method->entry_point_from_quick_compiled_code_;
      __ Ldr(lr, MemOperand(
          XRegisterFrom(callee_method),
       ArtMethod::EntryPointFromQuickCompiledCodeOffset(kArm64WordSize).Int32Value()));
      // lr()
      __ Blr(lr);
      break;
  }
}
```



可以看到只有 kCallArtMethod 才使用：



```
__ Ldr(lr, MemOperand(XRegisterFrom(callee_method),ArtMethod::EntryPointFromQuickCompiledCodeOffset(kArm64WordSize).Int32Value()));
```



生成了从 ArtMethod 加载 CodeEntry 的代码：



```
ldr lr [RegMethod, #CodeEntryOffset]
```



其他情况都是直接 B CodeEntry



## 8.0 之后



8.0 之后情况有所改观，说实话，从我的角度来说并没有感觉这项优化能带来多大的性能提升，所以 8.0 之后索性除了递归都先从 ArtMethod 里面找入口。



```
// Determines the location of the code pointer.
  enum class CodePtrLocation {
    // Recursive call, use local PC-relative call instruction.
    kCallSelf,

    // Use code pointer from the ArtMethod*.
    // Used when we don't know the target code. This is also the last-resort-kind used when
    // other kinds are unimplemented or impractical (i.e. slow) on a particular architecture.
    kCallArtMethod,
  };
```



### 代码生成



```
switch (invoke->GetCodePtrLocation()) {
    case HInvokeStaticOrDirect::CodePtrLocation::kCallSelf:
      {
        // Use a scope to help guarantee that `RecordPcInfo()` records the correct pc.
        ExactAssemblyScope eas(GetVIXLAssembler(),
                               kInstructionSize,
                               CodeBufferCheckScope::kExactSize);
        __ bl(&frame_entry_label_);
        RecordPcInfo(invoke, invoke->GetDexPc(), slow_path);
      }
      break;
    case HInvokeStaticOrDirect::CodePtrLocation::kCallArtMethod:
      // LR = callee_method->entry_point_from_quick_compiled_code_;
      __ Ldr(lr, MemOperand(
          XRegisterFrom(callee_method),
          ArtMethod::EntryPointFromQuickCompiledCodeOffset(kArm64PointerSize).Int32Value()));
      {
        // Use a scope to help guarantee that `RecordPcInfo()` records the correct pc.
        ExactAssemblyScope eas(GetVIXLAssembler(),
                               kInstructionSize,
                               CodeBufferCheckScope::kExactSize);
        // lr()
        __ blr(lr);
        RecordPcInfo(invoke, invoke->GetDexPc(), slow_path);
      }
      break;
  }
```



# invoke-virtual/interface



invoke-virtual/interface 默认走另外一套



```
{
    // Ensure that between load and MaybeRecordImplicitNullCheck there are no pools emitted.
    EmissionCheckScope guard(GetVIXLAssembler(), kMaxMacroInstructionSizeInBytes);
    // /* HeapReference<Class> */ temp = receiver->klass_
    __ Ldr(temp.W(), HeapOperandFrom(LocationFrom(receiver), class_offset));
    MaybeRecordImplicitNullCheck(invoke);
  }
  // Instead of simply (possibly) unpoisoning `temp` here, we should
  // emit a read barrier for the previous class reference load.
  // intermediate/temporary reference and because the current
  // concurrent copying collector keeps the from-space memory
  // intact/accessible until the end of the marking phase (the
  // concurrent copying collector may not in the future).
  GetAssembler()->MaybeUnpoisonHeapReference(temp.W());
  // temp = temp->GetMethodAt(method_offset);
  __ Ldr(temp, MemOperand(temp, method_offset));
  // lr = temp->GetEntryPoint();
  __ Ldr(lr, MemOperand(temp, entry_point.SizeValue()));
  {
    // Use a scope to help guarantee that `RecordPcInfo()` records the correct pc.
    ExactAssemblyScope eas(GetVIXLAssembler(), kInstructionSize, CodeBufferCheckScope::kExactSize);
    // lr();
    __ blr(lr);
    RecordPcInfo(invoke, invoke->GetDexPc(), slow_path);
  }
```



步骤如下：



- Class clazz = receiver.getClass()
- Method method = class.getMethodAt(Index);
- Blr method->CodeEntry



# InvokeRuntime



主要服务于需要在 Runtime 时期才能确定的 Invoke，例如类初始化 函数。(kQuickInitializeType)



InvokeRuntime 会从当前 Thread 中查找 CodeEntry：



```
void CodeGeneratorARM64::InvokeRuntime(int32_t entry_point_offset,
                                       HInstruction* instruction,
                                       uint32_t dex_pc,
                                       SlowPathCode* slow_path) {
  ValidateInvokeRuntime(instruction, slow_path);
  BlockPoolsScope block_pools(GetVIXLAssembler());
  __ Ldr(lr, MemOperand(tr, entry_point_offset));
  __ Blr(lr);
  RecordPcInfo(instruction, dex_pc, slow_path);
}
```



tr 就是线程寄存器，一般 ARM64 是 X19



所以代码出来一般长这样：



```
loc_3e6828:
mov        x0, x19
ldr        x20, [x0, #0x310]
blr        x20
```



# Intrinsics



ART 额外维护了一批系统函数的高效实现，这些高效实现利用了 CPU 的指令，直接跳过了方法调用。



```
  // System.arraycopy.
    case kIntrinsicSystemArrayCopyCharArray:
      return Intrinsics::kSystemArrayCopyChar;

    case kIntrinsicSystemArrayCopy:
      return Intrinsics::kSystemArrayCopy;

    // Thread.currentThread.
    case kIntrinsicCurrentThread:
      return Intrinsics::kThreadCurrentThread;
```



以 Thread.currentThread() 方法为例，此次调用在 intrinsics 的优化下变成了这段代码：



```
void IntrinsicCodeGeneratorARM64::VisitThreadCurrentThread(HInvoke* invoke) {
  codegen_->Load(Primitive::kPrimNot, WRegisterFrom(invoke->GetLocations()->Out()),
                 MemOperand(tr, Thread::PeerOffset<8>().Int32Value()));
}
```



最后出来的代码类似这样，直接就把 Thread.nativePeer ldr 给目标寄存器，根本不是方法调用了：



```
ldr x17, [x19, #PeerOffset]
```



# 结论



当 8.0 以上时，我们使用 ArtMethod 入口替换即可基本满足 Hook 需求。但如果 8.0 以下，如果不开启 debug 或者 deoptimize 的话，则必须使用 inline hook，否则会漏掉很多调用。