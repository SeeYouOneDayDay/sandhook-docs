# **性能优化 & Xposed 模块 & 阻止 VM Inline**

# 简介



前面有提到过 SandHook 需要手动写一个 Hook 方法作为 Hook 的入口，所以想要兼容 Xposed Callback 式 [API](https://so.csdn.net/so/search?q=API&spm=1001.2101.3001.7020) 就必须凭空生成一个 Hook 方法来转发 Xposed Callback 逻辑。



所以我选择了 DexMaker，但是 Dexmaker 生成代码并且加载的时候还是太慢了，尽管只需要第一次生成 (后面只需要加载即可)，第一次 Hook 一个函数大概需要耗费 100ms 的时间。



至于 Backup 方法，则可写可不写，如果不写，则可以直接 New 一个 Method，将原 ArtMethod 中的数据填充进去，但是这里有个比较蛋疼的问题。真实存在的 ArtMethod 是在 Non-Moving 堆中的，也就是说，原版的 ArtMethod 不会 Moving GC，而 New 出来的只是一个普通对象，GC 的时候地址就会移动，每次调用 backup 方法之前都需要检查是否被移动。
当然 SandHook 预先写了一堆空方法备用。



# 优化方案



其实优化方案很简单，依然是预先写一堆 Stub 函数，但是这些 Stub 变成了 hook 函数，并不仅仅在 Non-Moving Space 中占一个坑。他还需要接收原方法的参数，调用 XposedBridge，并且返回返回值。



## 参数接收



**Epic 是到栈中将参数一个个捞出来，而 SandHook 则将参数全部设为 long(32bit 为 int)**



原因很简单：



- 函数调用方将参数列表一个个 Append 进 (寄存器) 栈中
    在 64 位下所有的参数都占 8 个字节，long 也是 8 个字节。基本类型则为数值，而对象类型则为对象地址。32 位下，long 和 double 占 8 字节，其他均为 4 字节。
- 而 hook 方法作为接收方统一按 long(32bit 按 int) 接收，然后再将参数还原。
- 返回值和参数类似处理
- 参数列表可以尽量长，这样可以兼顾参数比较少的函数。



## 参数转换



如何转换？



### 对象



对象地址使用 jweak JavaVMExt::AddWeakGlobalRef(Thread* self, ObjPtrmirror::Object obj) 转换成 java 对象。



```
jweak JavaVMExt::AddWeakGlobalRef(Thread* self, ObjPtr<mirror::Object> obj) {
  if (obj == nullptr) {
    return nullptr;
  }
  MutexLock mu(self, *Locks::jni_weak_globals_lock_);
  // CMS needs this to block for concurrent reference processing because an object allocated during
  // the GC won't be marked and concurrent reference processing would incorrectly clear the JNI weak
  // ref. But CC (kUseReadBarrier == true) doesn't because of the to-space invariant.
  while (!kUseReadBarrier && UNLIKELY(!MayAccessWeakGlobals(self))) {
    // Check and run the empty checkpoint before blocking so the empty checkpoint will work in the
    // presence of threads blocking for weak ref access.
    self->CheckEmptyCheckpointFromWeakRefAccess(Locks::jni_weak_globals_lock_);
    weak_globals_add_condition_.WaitHoldingLocks(self);
  }
  std::string error_msg;
  IndirectRef ref = weak_globals_.Add(kIRTFirstSegment, obj, &error_msg);
  if (UNLIKELY(ref == nullptr)) {
    LOG(FATAL) << error_msg;
    UNREACHABLE();
  }
  return reinterpret_cast<jweak>(ref);
}
```



可以用 dlsym/fake_dlsym 搜索到。



### 基本类型



long 可以直接转成 int char byte short 等等，boolean 判断是不是 0 即可，float 和 double 则是存在浮点寄存器，而 long 是在通用寄存器，在参数列表和返回值中发现这两个类型直接转用 dexmaker 即可，一般这两种参数也少。
另外在 32bit 下 long/double 是 8 字节，参数列表中间夹了这两种类型参数会造成参数列表混乱，直接跳过走 Dexmaker。



**最后写了个 python 脚本自动生成 stub 函数，基本 9 成以上的函数 hook 直接走 stub，耗时仅仅 1 - 3ms。**



# 支持 Xposed 模块



最后结合 VirtualApp 简单实现了一个类似 VXP 的免 Root Xposed 容器，目前测了 Q++, 应用变量，XPrivacyLua，MDWechat 等模块可以使用。



https://github.com/ganyao114/SandVXposed



# 阻止 Inline



## 现象



VM 的 Inline 优化一直是我们 Hook 的最大阻碍，这里做个实验：



Android 7.1：



- 被 Hook 的测试方法



```
public String testInline(String a, int b, int c) {
        b++;
        c++;
        return "origin res" + a;
    }
```



- 调用测试方法的大循环



```
new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Log.e("TestInline", "res = " + testInline.testInline("xxx", 1, 3));
                }
            }
        }).start();
```



- Hook 点



主要修改了原方法的返回值



```
XposedHelpers.findAndHookMethod(TestInline.class, "testInline", String.class, int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult("hooked res");
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
            }
        });
```



- 实验结果



前 1 s，方法 Hook 正常，输出 Hook 后的返回值：



```
02-25 09:32:22.099 28532-28548/com.swift.sandhook E/TestInline: res = hooked res
```



1s 之后：



```
res = origin resxxx
    res = origin resxxx
    res = origin resxxx
    res = origin resxxx
    res = origin resxxx
    res = origin resxxx
    res = origin resxxx
```



这说明不到一秒，由于被 Hook 方法过于简单，而调用该方法的方法热度较高，调用者发生了 Inline 优化并且进行了栈上替换。



## 解决办法



研究 ART 源码发现：
当被 inline 方法的 code units 大于设置的阈值的时候，方法 Inline 失败。
这个阈值是 CompilerOptions -> inline_max_code_units_



```
const CompilerOptions& compiler_options = compiler_driver_->GetCompilerOptions();
  if ((compiler_options.GetInlineDepthLimit() == 0)
      || (compiler_options.GetInlineMaxCodeUnits() == 0)) {
    return;
  }

 bool should_inline = (compiler_options.GetInlineDepthLimit() > 0)
      && (compiler_options.GetInlineMaxCodeUnits() > 0);
  if (!should_inline) {
    return;
  }

  size_t inline_max_code_units = compiler_driver_->GetCompilerOptions().GetInlineMaxCodeUnits();
  if (code_item->insns_size_in_code_units_ > inline_max_code_units) {
    VLOG(compiler) << "Method " << PrettyMethod(method)
                   << " is too big to inline: "
                   << code_item->insns_size_in_code_units_
                   << " > "
                   << inline_max_code_units;
    return false;
  }
```



那么想办法把这个阈值设为 0 就可以了。



经过搜索，CompilerOptions 一般与 JitCompiler 绑定：



```
class JitCompiler {
 public:
  static JitCompiler* Create();
  virtual ~JitCompiler();

..............
 private:
  std::unique_ptr<CompilerOptions> compiler_options_;

	............
};

}  // namespace jit
}  // namespace art
```



而 ART 的 JitCompiler 为全局单例：



```
  // JIT compiler
  static void* jit_compiler_handle_;

  jit->dump_info_on_shutdown_ = options->DumpJitInfoOnShutdown();
  if (jit_compiler_handle_ == nullptr && !LoadCompiler(error_msg)) {
    return nullptr;
  }

  jit_compiler_handle_ = (jit_load_)(&will_generate_debug_symbols);

extern "C" void* jit_load(bool* generate_debug_info) {
  VLOG(jit) << "loading jit compiler";
  auto* const jit_compiler = JitCompiler::Create();
  CHECK(jit_compiler != nullptr);
  *generate_debug_info = jit_compiler->GetCompilerOptions()->GetGenerateDebugInfo();
  VLOG(jit) << "Done loading jit compiler";
  return jit_compiler;
}
```



ok，那么我们就得到了 “static void* jit_compiler_handle_” 的 C++ 符号 “_ZN3art3jit3Jit20jit_compiler_handle_E“



最后修改里面的值就可以了。



# ART Invoke 代码生成分析



https://blog.csdn.net/ganyao939543405/article/details/88079544