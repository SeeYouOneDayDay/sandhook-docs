## **Android ART Hook 实现**

# 简介



[Github](https://so.csdn.net/so/search?q=Github&spm=1001.2101.3001.7020): https://github.com/ganyao114/SandHook



关于 Hook，一直是比较小众的需求。本人公司有在做 Android Sandbox(类似 VA)，算是比较依赖 Hook 的产品，好在 Android Framework 大量使用了代理模式。所以也不用费劲在 VM 层作文章了，直接用动态代理即可。
然而，VM 层的 Java Hook 还是有些需求的，除了测试时候的性能监控，AOP 等之外。大多还是用在了黑灰产，Android Sandbox + ART Hook = 免越狱的 Xposed。
正好拜读了 epic 和 yahfa 的源码，也是有所收获并且尝试着写了这个框架。



# SandHook



## 特点



- 主要基于 inline hook，条件不允许时进行 ArtMethod 入口替换
- 接口为注解式，较为友好
- 支持直接 Hook，也支持在插件场景中的 Hook
- 本人极少用 C++，所以 native 代码有些 java style，喜欢 java 的同学还是能一眼看懂的 (现在 C++ 是好写多了，基本感受不到和 Java 有多大区别)。汇编代码也很好懂。



## 支持 OS



5.0 - 9.0



## 支持架构



- ARM32
- Thumb-2
- ARM64



## Hook 范围



- 对象方法
- Static 方法
- 构造方法
- JNI 方法
- 系统方法
- 抽象方法 (非常不可靠)



基本除了抽象方法都能 Hook，抽象方法实在无能为力：大多数情况不走 ArtMethod，即便替换 vtable，依然会遗漏很多实现。



## 使用方法



- 依赖：



```
implementation 'com.swift.sandhook:hooklib:0.0.1'
```



- 写 Hook 项：



```
@HookClass(Activity.class)
//@HookReflectClass("android.app.Activity")
public class ActivityHooker {
    
    // can invoke to call origin
    @HookMethodBackup("onCreate")
    @MethodParams(Bundle.class)
    static Method onCreateBackup;

    @HookMethodBackup("onPause")
    static Method onPauseBackup;

    @HookMethod("onCreate")
    @MethodParams(Bundle.class)
    //@MethodReflectParams("android.os.Bundle")
    public static void onCreate(Activity thiz, Bundle bundle) {
        Log.e("ActivityHooker", "hooked onCreate success " + thiz);
        onCreateBackup(thiz, bundle);
    }

    @HookMethodBackup("onCreate")
    @MethodParams(Bundle.class)
    public static void onCreateBackup(Activity thiz, Bundle bundle) {
        //invoke self to kill inline
        onCreateBackup(thiz, bundle);
    }

    @HookMethod("onPause")
    public static void onPause(Activity thiz) {
        Log.e("ActivityHooker", "hooked onPause success " + thiz);
        onPauseBackup(thiz);
    }

    @HookMethodBackup("onPause")
    public static void onPauseBackup(Activity thiz) {
        //invoke self to kill inline
        onPauseBackup(thiz);
    }

}
```



- 添加 Hook 项：



```
SandHook.addHookClass(JniHooker.class,
                    CtrHook.class,
                    LogHooker.class,
                    CustmizeHooker.class,
                    ActivityHooker.class,
                    ObjectHooker.class);
```



- 如果想在插件里配置 Hook 项：
    在插件里 provider 注解：



```
provided 'com.swift.sandhook:hookannotation:0.0.1'
```



### Hook 项



Hook 项由 Hook 类， Hook 方法，Backup 方法组成，如果不需要掉用原方法则不需要写 Backup 方法。



- Hook/Backup 方法必须是 static，并且 Hook/Backup 方法描述相同
- Hook/Backup 方法返回类型，参数列表类型必须与原方法匹配，类型必须可以 Cast，不要求完全一样。
- 如果原方法是非静态方法，Hook/Backup 方法第一个参数必须是 this



如果 OS <= 5.1 ，backup 方法建议调用自己 (或加 try catch)，否则会被内联进 hook 方法，导致无法调用原方法。当然你也可以使用 invoke 调用原方法。



# 实现



## 基本原理



简单的来说就是 inline hook。
将目标方法的前 (arm32-2/arm64-4) 行代码拷贝出来，换上跳转代码跳到一个汇编写的二级跳板，二级跳板再确定当前方法需要 Hook 之后，跳转到 Hook 方法。
除此之外，考虑到 N 以上方法有可能是解释执行，手动调用 JIT 有可能编译失败 (很少见)，也保留了 ArtMethod 入口替换的逻辑 (类似 YAHFA)。



详细步骤如下：



- 初始化，得出 ArtMethod 大小，以及内部一些元素的偏移
- 解析 Hooker 项，得到 origin，hook，backup 三个 Method 对象
- 检查 Hooker/Backup 方法和原方法签名是否匹配
- 手动 resolve 静态方法
- resolve cache dex，保证 Hook 方法能找到 backup 方法
- 抽象方法只能走 ArtMethod 入口替换
- 检查是否是未编译，未编译 >= 7.0 尝试编译。
- < 7.0 或者编译失败走 ArtMethod 入口替换
- 编译成功进行 inline hook
- 如果需要备份方法，拷贝原方法 ArtMethod 覆盖 Backup 的 ArtMethod
- 为跳板分配可执行内存
- 备份代码到二级跳板
- native code 入口插入跳转代码
- 填充二级跳板
- 如果需要备份，准备 CallOrigin 跳板并且塞到 backup 方法的 ArtMethod



## 初始化



这一步主要是确定 ArtMethod 的内存布局



- 首先用两个相邻的 ArtMethod 相减得到 ArtMethod 的大小
- 而后有些属性可以在 Java 层查到具体数值 (accessFlags 等)，拿着数值到 ArtMethod 里面搜索的到对应项目的偏移。
- 实在得不到的只能通过系统版本和指针长度区分。



## resolve 静态方法

[最完整 Hook 的使用 Demozip ![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231059505.png) 1 星 超过 10% 的资源 164KB![img](https://csdnimg.cn/release/blogv2/dist/components/img/arrowDownWhite.png)下载](https://download.csdn.net/download/oweiwancheng12/11065279)



静态方法是懒加载的，在所在类未被调用前，是未被 resolve 的状态，此时该类的 code entry 只是一个公用的 resolve static stub，我们 inline hook 这个公共入口没有意义。
我们只需要手动 invoke 该方法即可解决，invoke(new Object())，强行塞给他一个 receiver，既达到了 resolve 的目的，也防止方法被成功调用。
hook 方法也是 static 方法，所以也需要 resolve



## resolve DexCache



<= 9.0 时，每个 Dex 存在一个 DexCache，主要目的是跳到下一个方法执行之前，如果是下一个方法在本 Dex 内部，可以通过 index 搜索到下一个方法的 ArtMethod。
而 Hook 方法需要调用的 Backup 方法已经被原方法的 ArtMethod 覆盖，所以需要我们手动填充 DexCache 的 resolvedMethods。



有两条路可走：



- 在 <= 6.0 时，Java 层还保留着 ArtMethod 以及 DexCache 的 Java 对象，并且和 Native 层有映射关系，我们直接填充 Java 层的 resolvedMethods 数组即可。
- 当 > 6.0 时，Java 层 resolvedMethods 只是 Native 层的地址，7.0 后完全移除，就需要我们在 native 层操作。



## 编译方法



这点参考 epic 调用 [libart-compiler.so](http://libart-compiler.so/) 中的 jitcompile 方法手动编译该类。



## 关于寄存器的使用



跳板代码至少需要使用一个寄存器
在 ARM64 中：



```
// Method register on invoke.
// 储存正在调用的方法
static const vixl::aarch64::Register kArtMethodRegister = vixl::aarch64::x0;

//参数传递
static const vixl::aarch64::Register kParameterCoreRegisters[] = {
  vixl::aarch64::x1,
  vixl::aarch64::x2,
  vixl::aarch64::x3,
  vixl::aarch64::x4,
  vixl::aarch64::x5,
  vixl::aarch64::x6,
  vixl::aarch64::x7
};

//
const vixl::aarch64::CPURegList vixl_reserved_core_registers(vixl::aarch64::ip0,
                                                             vixl::aarch64::ip1);

//浮点参数
static const vixl::aarch64::FPRegister kParameterFPRegisters[] = {
  vixl::aarch64::d0,
  vixl::aarch64::d1,
  vixl::aarch64::d2,
  vixl::aarch64::d3,
  vixl::aarch64::d4,
  vixl::aarch64::d5,
  vixl::aarch64::d6,
  vixl::aarch64::d7
};

// Thread Register.
// 线程
const vixl::aarch64::Register tr = vixl::aarch64::x19;

// Marking Register.
// GC 标记
const vixl::aarch64::Register mr = vixl::aarch64::x20;

// Callee-save registers AAPCS64, without x19 (Thread Register) (nor
// x20 (Marking Register) when emitting Baker read barriers).
const vixl::aarch64::CPURegList callee_saved_core_registers(
    vixl::aarch64::CPURegister::kRegister,
    vixl::aarch64::kXRegSize,
    ((kEmitCompilerReadBarrier && kUseBakerReadBarrier)
         ? vixl::aarch64::x21.GetCode()
         : vixl::aarch64::x20.GetCode()),
     vixl::aarch64::x30.GetCode());
```



X22 - X28 被 ART 内部用作 Callee Saved



看上去只有被定义为 IP0/IP1 的 X16/X17 可以用，但是 X16 在跳板中被使用。
最后我选择了 X17。



至于 ARM32 则没得选，只有一个 IP(R12)。



## 开始内联





![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231059677.png)





结构如上图所属：



- 首先我们需要一块可以执行的内存，mmap 可以解决。
- 然后原方法的 code entry 是不能写的，memunportect 可以解决
- 当 >= 7.0 时，JIT 可能会和我们同时修改原方法，在 accessFlags 中：

[Android-AndroidARTHook 实现 - SandHook.zip ![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231059505.png) 0 星 超过 10% 的资源 803KB![img](https://csdnimg.cn/release/blogv2/dist/components/img/arrowDownWhite.png)下载](https://download.csdn.net/download/weixin_39840924/11535368)



```
/ Set by the verifier for a method we do not want the compiler to compile.
static constexpr uint32_t kAccCompileDontBother =     0x02000000;  // method (runtime)
```



jit 就会忽略该方法



## Thumb-2



Thumb 指令必须所在内存必须满足：



```
bool isThumbCode(Size codeAddr) {
            return (codeAddr & 0x1) == 0x1;
        }
```



所以 Code Entry 和跳板跳转的地址都必须加以处理



## 关于 Code Entry 可能重复的问题



除了一些公共的 Stub 之外，epic 作者的文章中提到如果逻辑类似的两个方法也有可能入口相同。这部分我实验没有发现过。
不过为了防止这种情况的发生，还是和 epic 一样做了判断处理，在 Hook 多个相同入口的时候，跳板形成了 “责任链模式”。



# 最后上一下部分代码



## 跳板



```
#if defined(__aarch64__)

#define Reg0 x17
#define Reg1 x16
#define RegMethod x0

FUNCTION_START(REPLACEMENT_HOOK_TRAMPOLINE)
    ldr RegMethod, addr_art_method
    ldr Reg0, addr_code_entry
    ldr Reg0, [Reg0]
    br Reg0
addr_art_method:
    .long 0
    .long 0
addr_code_entry:
    .long 0
    .long 0
FUNCTION_END(REPLACEMENT_HOOK_TRAMPOLINE)

#define SIZE_JUMP #0x10
FUNCTION_START(DIRECT_JUMP_TRAMPOLINE)
    ldr Reg0, addr_target
    br Reg0
addr_target:
    .long 0
    .long 0
FUNCTION_END(DIRECT_JUMP_TRAMPOLINE)

FUNCTION_START(INLINE_HOOK_TRAMPOLINE)
    ldr Reg0, origin_art_method
    cmp RegMethod, Reg0
    bne origin_code
    ldr RegMethod, hook_art_method
    ldr Reg0, addr_hook_code_entry
    ldr Reg0, [Reg0]
    br Reg0
origin_code:
    .long 0
    .long 0
    .long 0
    .long 0
    ldr Reg0, addr_origin_code_entry
    ldr Reg0, [Reg0]
    add Reg0, Reg0, SIZE_JUMP
    br Reg0
origin_art_method:
    .long 0
    .long 0
addr_origin_code_entry:
    .long 0
    .long 0
hook_art_method:
    .long 0
    .long 0
addr_hook_code_entry:
    .long 0
    .long 0
FUNCTION_END(INLINE_HOOK_TRAMPOLINE)

FUNCTION_START(CALL_ORIGIN_TRAMPOLINE)
    ldr RegMethod, call_origin_art_method
    ldr Reg0, addr_call_origin_code
    br Reg0
call_origin_art_method:
    .long 0
    .long 0
addr_call_origin_code:
    .long 0
    .long 0
FUNCTION_END(CALL_ORIGIN_TRAMPOLINE)

#endif
```



## 跳板安装



```
HookTrampoline* TrampolineManager::installReplacementTrampoline(mirror::ArtMethod *originMethod,
                                                                    mirror::ArtMethod *hookMethod,
                                                                    mirror::ArtMethod *backupMethod) {
        AutoLock autoLock(installLock);

        if (trampolines.count(originMethod) != 0)
            return getHookTrampoline(originMethod);
        HookTrampoline* hookTrampoline = new HookTrampoline();
        ReplacementHookTrampoline* replacementHookTrampoline = nullptr;
        Code replacementHookTrampolineSpace;

        replacementHookTrampoline = new ReplacementHookTrampoline();
        replacementHookTrampoline->init();
        replacementHookTrampolineSpace = allocExecuteSpace(replacementHookTrampoline->getCodeLen());
        if (replacementHookTrampolineSpace == 0)
            goto label_error;
        replacementHookTrampoline->setExecuteSpace(replacementHookTrampolineSpace);
        replacementHookTrampoline->setEntryCodeOffset(quickCompileOffset);
        replacementHookTrampoline->setHookMethod(reinterpret_cast<Code>(hookMethod));
        hookTrampoline->replacement = replacementHookTrampoline;

        trampolines[originMethod] = hookTrampoline;
        return hookTrampoline;

    label_error:
        delete hookTrampoline;
        delete replacementHookTrampoline;
        return nullptr;
    }

    HookTrampoline* TrampolineManager::installInlineTrampoline(mirror::ArtMethod *originMethod,
                                                               mirror::ArtMethod *hookMethod,
                                                               mirror::ArtMethod *backupMethod) {

        AutoLock autoLock(installLock);

        if (trampolines.count(originMethod) != 0)
            return getHookTrampoline(originMethod);
        HookTrampoline* hookTrampoline = new HookTrampoline();
        InlineHookTrampoline* inlineHookTrampoline = nullptr;
        DirectJumpTrampoline* directJumpTrampoline = nullptr;
        CallOriginTrampoline* callOriginTrampoline = nullptr;
        Code inlineHookTrampolineSpace;
        Code callOriginTrampolineSpace;
        Code originEntry;

        //生成二段跳板
        inlineHookTrampoline = new InlineHookTrampoline();
        checkThumbCode(inlineHookTrampoline, getEntryCode(originMethod));
        inlineHookTrampoline->init();
        inlineHookTrampolineSpace = allocExecuteSpace(inlineHookTrampoline->getCodeLen());
        if (inlineHookTrampolineSpace == 0)
            goto label_error;
        inlineHookTrampoline->setExecuteSpace(inlineHookTrampolineSpace);
        inlineHookTrampoline->setEntryCodeOffset(quickCompileOffset);
        inlineHookTrampoline->setOriginMethod(reinterpret_cast<Code>(originMethod));
        inlineHookTrampoline->setHookMethod(reinterpret_cast<Code>(hookMethod));
        if (inlineHookTrampoline->isThumbCode()) {
            inlineHookTrampoline->setOriginCode(inlineHookTrampoline->getThumbCodeAddress(getEntryCode(originMethod)));
        } else {
            inlineHookTrampoline->setOriginCode(getEntryCode(originMethod));
        }
        hookTrampoline->inlineSecondory = inlineHookTrampoline;

        //注入 EntryCode
        directJumpTrampoline = new DirectJumpTrampoline();
        checkThumbCode(directJumpTrampoline, getEntryCode(originMethod));
        directJumpTrampoline->init();
        originEntry = getEntryCode(originMethod);
        if (!memUnprotect(reinterpret_cast<Size>(originEntry), directJumpTrampoline->getCodeLen())) {
            goto label_error;
        }

        if (directJumpTrampoline->isThumbCode()) {
            originEntry = directJumpTrampoline->getThumbCodeAddress(originEntry);
        }

        directJumpTrampoline->setExecuteSpace(originEntry);
        directJumpTrampoline->setJumpTarget(inlineHookTrampoline->getCode());
        hookTrampoline->inlineJump = directJumpTrampoline;

        //备份原始方法
        if (backupMethod != nullptr) {
            callOriginTrampoline = new CallOriginTrampoline();
            checkThumbCode(callOriginTrampoline, getEntryCode(originMethod));
            callOriginTrampoline->init();
            callOriginTrampolineSpace = allocExecuteSpace(callOriginTrampoline->getCodeLen());
            if (callOriginTrampolineSpace == 0)
                goto label_error;
            callOriginTrampoline->setExecuteSpace(callOriginTrampolineSpace);
            callOriginTrampoline->setOriginMethod(reinterpret_cast<Code>(originMethod));
            Code originCode = nullptr;
            if (callOriginTrampoline->isThumbCode()) {
                originCode = callOriginTrampoline->getThumbCodePcAddress(inlineHookTrampoline->getCallOriginCode());
                #if defined(__arm__)
                Code originRemCode = callOriginTrampoline->getThumbCodePcAddress(originEntry + directJumpTrampoline->getCodeLen());
                Size offset = originRemCode - getEntryCode(originMethod);
                if (offset != directJumpTrampoline->getCodeLen()) {
                    Code32Bit offset32;
                    offset32.code = offset;
                    unsigned char offsetOP = callOriginTrampoline->isBigEnd() ? offset32.op.op2 : offset32.op.op1;
                    callOriginTrampoline->tweakOpImm(OFFSET_INLINE_OP_ORIGIN_OFFSET_CODE, offsetOP);
                }
                #endif
            } else {
                originCode = inlineHookTrampoline->getCallOriginCode();
            }
            callOriginTrampoline->setOriginCode(originCode);
            hookTrampoline->callOrigin = callOriginTrampoline;
        }

        trampolines[originMethod] = hookTrampoline;
        return hookTrampoline;

    label_error:
        delete hookTrampoline;
        if (inlineHookTrampoline != nullptr) {
            delete inlineHookTrampoline;
        }
        if (directJumpTrampoline != nullptr) {
            delete directJumpTrampoline;
        }
        if (callOriginTrampoline != nullptr) {
            delete callOriginTrampoline;
        }
        return nullptr;
    }
```



## 内存分配



```
Code TrampolineManager::allocExecuteSpace(Size size) {
        if (size > MMAP_PAGE_SIZE)
            return 0;
        AutoLock autoLock(allocSpaceLock);
        void* mmapRes;
        Code exeSpace = 0;
        if (executeSpaceList.size() == 0) {
            goto label_alloc_new_space;
        } else if (executePageOffset + size > MMAP_PAGE_SIZE) {
            goto label_alloc_new_space;
        } else {
            exeSpace = executeSpaceList.back();
            Code retSpace = exeSpace + executePageOffset;
            executePageOffset += size;
            return retSpace;
        }
    label_alloc_new_space:
        mmapRes = mmap(NULL, MMAP_PAGE_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC,
                             MAP_ANON | MAP_PRIVATE, -1, 0);
        if (mmapRes == MAP_FAILED) {
            return 0;
        }
        exeSpace = static_cast<Code>(mmapRes);
        executeSpaceList.push_back(exeSpace);
        executePageOffset = size;
        return exeSpace;
    }
```



# 第二弹



[SandHook 第二弹 - Xposed API 兼容 & 指令检查 & 进程注入](https://blog.csdn.net/ganyao939543405/article/details/87092431)