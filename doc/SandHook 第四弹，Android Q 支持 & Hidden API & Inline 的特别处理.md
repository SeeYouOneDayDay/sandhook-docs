# SandHook 第四弹，Android Q 支持 & Hidden API & Inline 的特别处理

# SandHook



目前支持 4.4 - 10.0
32/64 bit
支持 Xposed [API](https://so.csdn.net/so/search?q=API&spm=1001.2101.3001.7020)



[Github](https://so.csdn.net/so/search?q=Github&spm=1001.2101.3001.7020)：



https://github.com/ganyao114/SandHook



# Android Q 支持



## Hidden Api



这个是从 Android P 就开始引入的[反射](https://so.csdn.net/so/search?q=反射&spm=1001.2101.3001.7020)限制机制。



目前来说有几种方案：



- Hook 法，Hook 某些判断函数，修改 ART 对限制 API 的判断流程
- Hidden API 在内部抽象为 Policy，修改全局的 Policy 策略，一般是 Runtime 中的某个 Flag
- 系统函数调 Hidden API 是 OK 的，想办法让 ART 误以为是系统函数调用 API，一般是修改 ClassLoader



P 上，判断函数较为集中，Policy 的 Flag 也较为好搜索，然而到了 Q 上就多了，至于在 Runtime 中搜索 Flag，由于 Runtime 是个巨大的结构体，这并不是一个健壮的方法。。。



最后是想办法让 ART 误以为是系统函数调用 API，还有一种办法是双重反射，即用反射调用 Class.getDeclareMethod 之类的 API 去使用反射，也能达到目的。PS 这个方法还是在贴吧偶然看到的，OpenJDK 也有这个问题。。。。后面就简单了，依据此法找到 Hidden API 的开关方法，调用即可。



Github：



https://github.com/ganyao114/AndroidHiddenApi



## AccessFlag



这个比较好解决，Android Q 上也是因为 Hidden Api 机制为每个方法增加了一个 Flag，导致我使用预测 Flag 值在 ArtMethod 中搜索 Offset 未能搜到。



kAccPublicApi = 0x10000000 代表此方法 / Field 为公开 API



```
uint32_t accessFlag = getIntFromJava(jniEnv, "com/swift/sandhook/SandHook",
                                                 "testAccessFlag");
            if (accessFlag == 0) {
                accessFlag = 524313;
                //kAccPublicApi
                if (SDK_INT >= ANDROID_Q) {
                    accessFlag |= 0x10000000;
                }
            }
            int offset = findOffset(p, getParentSize(), 2, accessFlag);
```



## 一些符号的变化



[libart-compiler.so](http://libart-compiler.so/) 中的 jit_compile_method 增加了一个参数。



另外前面通过修改 CompilerOptions 中的参数来达到阻止 JIT Inline 的目的，现在 Android Q 会在某种情况下刷新 CompilerOptions，这个方法和 jit_compile_method 一样，在 JIT 初始化的时候从 [libart-compiler.so](http://libart-compiler.so/) 加载到 jit_update_options_ 全局变量中，符号搜索找到这个变量替换成空实现即可。



## fake_dlopen



N 以上搜索符号的库 avs333/Nougat_dlfunctions 中，从 maps 内存布局解析 module base 的逻辑写的有点死



```
while(!found && fgets(buff, sizeof(buff), maps)) 
		    if(strstr(buff,"r-xp") && strstr(buff,libpath)) found = 1;
```



到了 Android Q 上，一个 so 很容易被内核映射成好几段：



```
70f410a000-70f41ca000 r--p 00000000 fc:00 2306 /system/lib64/libart-compiler.so
70f41ca000-70f43d0000 --xp 000c0000 fc:00 2306 /system/lib64/libart-compiler.so。70f43d0000-70f43d1000 rw-p 002c6000 fc:00 2306 /system/lib64/libart-compiler.so。70f43d1000-70f43e4000 r--p 002c7000 fc:00 2306 /system/lib64/libart-compiler.so
```



这样写就有问题了，ELF 头部并不是 r-xp。修改这个即可



## 其他问题



Q 中 JNI 方法默认全部 AOT，这导致 Frida 的方案会有问题，Frida 在 Q 上应该暂时不能用。



# Inline 处理



JIT 的 Inline 优化可以通过修改 CompilerOptions 缓解，然而 AOT 的则不受我们控制。好在 N 以上默认新装 APK 部分是不会 AOT 的，如果实在有特殊情况则只能单独考量。



解决方法就是如果能预先知道 Inline 的 Caller 的话，强制让 Caller 解释执行即可。



逆编译的方法很简单，只需要把 CodeEntry 入口替换回解释执行的跳板即可。



那现在的问题就是如何准确无误地获取：



```
//普通方法
art_quick_to_interpreter_bridge
//JNI 方法
art_quick_generic_jni_trampoline
```



虽然前面是通过一个从不调用的方法拿到这个入口，然而还是可能有被 AOT 的情况。一旦被 AOT，替换错入口则会导致严重后果。



然而不凑巧的是，art_quick_to_interpreter_bridge 这两个入口虽然保留了符号，但并不是动态链接符号，所以 dlsym / fake_dlsym 是搜不到的。



这个符号在符号表 SHT_SYMTAB 中，所以需要修改实现。



Github：



https://github.com/ganyao114/AndroidELF