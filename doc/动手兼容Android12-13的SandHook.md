动手兼容 Android12/13 的 SandHook

  虽然 Pine 和 lsplant 和 yahfa 已经支持 Android12/13 了，而 SandHook 开源版一致没有支持以及更新，但是容器里一直用着 SandHook，本着不想麻烦和学习的角度，自己动手尝试兼容一下 Android12/13 的 SandHook。

# **一、ART 偏移兼容**

## **1.class_linker**

  直接运行 SandHook 中的 demo 项目看看会不会 Crash，里面有一些现成的 hook 测试，例如基于注解的和 XposedBridge 的，以及测试不同场景下例如 JNI / 静态方法 / 构造方法 / 普通方法等的测试 case，根据报错去修复是最直接的兼容思路。编译运行之后果不其然闪退了，logcat 查看崩溃日志，根据报错信息去定位崩溃原因，发现是 ART 相关偏移问题，从手机里 pull 下来对应的 libart.so，结合代码和 ida 分析一下，定位到是 class_linker 的偏移在 android12 之后有变化，修复之：



![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231058656.png)



## **2.kAccPreCompiled**

  为了防止 hook 之后方法入口被 JIT 重新替换导致 Hook 失效，需要将方法设置为不可编译，这里也需要兼容一下 kAccPreCompiled 的值，android12 之后重新变为了 0x00800000u。



![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231058721.png)



## **3.dexMethodIndex**

  这里是最后一个崩溃，排查了很久，最后发现是设置 hotnesscount 的时候导致的崩溃，原因为 dexMethodIndex 的偏移计算错误，导致 hotnesscount 的偏移一错误



![img](https://mmbiz.qpic.cn/mmbiz_png/CKgIia7tlRsibibP0QIVXU2HiajLeFRbfRC5icRDqPHGcUuQuWrMsJMClgkUibbtL32dCAI6MOvfic7yPBv4icVrPW0PSA/640?wx_fmt=png)



# **二、Hook 失效问题**

  在不崩溃之后，发现除了一个系统类 java.lang.Thread 的构造方法 Hook 正常打印出来，其他 Hook 函数均为调用，开始在想有没有可能 sandhook 的入口模式地址替换有问题，打印了一下发现成功替换了，于是替换成 inline 模式测试一下发现仍然没有 hook 上，思考了很久，lsplant 和 pine 的代码也看了很久，怀疑 AOT 编译有改动，oatdump 出来看了下仍然是 dalvik 指令，遂感觉应该是某种原因导致方法调用时没有走方法入口 entry_point_from_quick_compiled_code_即走了解释器模式导致的，如何避免解释器模式也是诸多 ART Hook 框架需要解决的问题，更多相关信息可以参考 sandhook 的 ReadMe 文档，对方法入口和 inline hook 模式以及方法调用流程讲的非常详细，最后也是在 Lsplant 中找到了解决方案，不得不说，lsplant 代码实现优雅还精简有力，仅保留核心操作即可兼容诸多版本：



![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231058785.png)



原因可以参考 Android 源码里：



![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231058785.png)



如果 javaDebuggable 为 true，那么一定不会使用 aotCode 作为方法入口，那么方法就会被解释执行从而导致 hook 失效。

# ![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231058816.png)** ****三、测试效果** 

最后，因为原来 sandhook 测试 case 太多还有点乱，而且通过日志也不太方便看出来 Hook 兼容的效果，所以优化了一下 demo 页面方便直观看出优化效果：



![img](https://raw.githubusercontent.com/hhhaiai/Picture/main/img/202208231058857.png)



兼容后的完整代码在这里：https://github.com/AlienwareHe/sandhook-docs

因为本人水平太菜，如果有什么兼容不到位以及不合理问题，欢迎一起探讨交流学习。

# **四、Thanks**

- epic 静态函数 hook:https://www.gitroy.com/?p=389
- Lsplant
- Pine
- Yahfa