# Xposed [API](https://so.csdn.net/so/search?q=API&spm=1001.2101.3001.7020) 兼容



由于 SandHook 需要手动写一个签名与目标方法相同的 Hook 方法，如果想把 API 包装成类 Xposed 的 Callback 式 API 是比较困难的，首先参数列表的解析就需要另外实现。



Epic 是用写好的一堆 Stub 函数进行分发，SandHook 参考了 EdXposed(YHAFA 的封装) 使用 Dexmaker 动态生成 Hook 函数。动态生成的函数包含参数列表保存以及转入 Xposed API 的逻辑。其实比较推荐 Byte Buddy，API 比较友好性能也不错。主要 EdXposed 使用 Dexmaker 已经实现过，所以就不做重复工作了。



- 效果如下



```
//setup for xposed
XposedCompat.cacheDir = getCacheDir();
XposedCompat.context = this;
XposedCompat.classLoader = getClassLoader();
XposedCompat.isFirstApplication= true;  
//do hook
XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
          super.beforeHookedMethod(param);
          Log.e("XposedCompat", "beforeHookedMethod: " + param.method.getName());
      }

      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
          super.afterHookedMethod(param);
          Log.e("XposedCompat", "afterHookedMethod: " + param.method.getName());
      }
});
```



# 指令检查



主要分为两个方面：



- 检查是否包含 PC 寄存器相关的指令
- 检查待替换的指令 Thumb16 / Thumb32 混合的问题



## PC 相关



修复 PC 寄存器相关的指令是 inline hook 的重要步骤，之所以 SandHook 之前不做，一是因为 OAT/JIT 出来的 Code 前几行基本都是栈操作指令，二是出来的 Code 都使用绝对地址。前几行 Code 包含 PC 相关代码的情况非常少见。。。。所以这个这个检查就当保险起见了。



## Thumb16/32 混合的问题



这个困扰了我很久，之前在 9.0 32bit 下使用 inline hook 一直会莫名其妙挂掉。最后调试发现，前几行 32 位 thumb2 可能是 Thumb16/32 指令混合的。一级跳板是 4 字节对齐的，而备份出的原指令则肯能不是 4 字节对齐的。。。
这样等于一个 4 字节的原指令可能会被截取一半，最终导致非法指令。
之所以前面没有关注是因为 9.0 之前前几个指令基本都是 Thumb32。



# 进程注入



Hook 想要应用到目标 APP 现在有几个方案:



- Root 进程注入，单个进程注入或者注入 zygote 进程，流程基本差不多 (XX 助手)
- 非 Root 进程注入，需要在沙箱 / 双开环境内，因为 UID 相同免去了 Root 的步骤，其余与上面类似。(XX 助手)
- 非 Root 插件加载，这个需要对沙箱 / 双开框架进行修改，在目标 App 插件代码里面主动加载 Hook 代码。(VirtualXposed)
- 解包目标 App 植入 Inject 代码，在重新打包安装。运行时被植入的 Inject 逻辑再将 Hook 代码加载到目标 App 进程。这个需要处理目标 App 的防打包逻辑，例如 Hook 签名验证等等。(太极)



总的来说各有优缺点：



- 进程注入主要怕目标进程做了反调试。
- 沙箱插件加载主要依赖沙箱本身的稳定性，和目标 App 的反 VM 逻辑。
- 解包植入代码主要怕目标 App 的包验证等等。至少刺激战场没玩到一局就被封了 233



## 沙箱进程注入



在沙箱中注入 SandHook 逻辑，简单用网上的祖传代码改了下，写了个 demo，目前测了 7.0 / 8.0
，8.0 以上 64 位目标进程暂时不能注入。
主要是 8.0 以上，dlopen 有限制，需要使用 linker 内部的函数，而 dlsym/fake_dlsym 并不能搜到符号，需要手动解析 ELF 去搜符号。
暂时只有 ELF32 的代码，不过 ELF64 有空也可以适配一下。



https://github.com/ganyao114/SandBoxHookPlugin



# 后续



[SandHook 第三弹 - 性能优化 & Xposed 模块](https://blog.csdn.net/ganyao939543405/article/details/87885893)