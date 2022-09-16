package com.swift.sandhook.xposedcompat.methodgen;

import android.os.Trace;

import com.swift.sandhook.SandHook;
import com.swift.sandhook.blacklist.HookBlackList;
import com.swift.sandhook.wrapper.HookWrapper;
import com.swift.sandhook.xposedcompat.XposedCompat;
import com.swift.sandhook.xposedcompat.classloaders.ProxyClassLoader;
import com.swift.sandhook.xposedcompat.hookstub.HookMethodEntity;
import com.swift.sandhook.xposedcompat.hookstub.HookStubManager;
import com.swift.sandhook.xposedcompat.utils.DexLog;
import com.swift.sandhook.xposedcompat.utils.FileUtils;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

public final class DynamicBridge {

    private static HookMaker defaultHookMaker = XposedCompat.useNewCallBackup ? new HookerDexMakerNew() : new HookerDexMaker();
    private static final AtomicBoolean dexPathInited = new AtomicBoolean(false);
    private static File dexDir;

    //use internal stubs  内部定义方法拦截
    private final static Map<Member, HookMethodEntity> entityMap = new HashMap<>();
    //use dex maker 通过dex maker生成拦截  原方法:调用方法
    private final static HashMap<Member, Method> hookedInfo = new HashMap<>();

    // hook方法、构造入口
    public static synchronized void hookMethod(Member originMethod, XposedBridge.AdditionalHookInfo additionalHookInfo) {

        // 检查hook对象，支持构造、方法，不支持抽象类、接口
        if (!checkMember(originMethod)) {
            return;
        }

        // 拦截列表中已经包含,则跳过
        if (hookedInfo.containsKey(originMethod) || entityMap.containsKey(originMethod)) {
            DexLog.w("already hook method:" + originMethod.toString());
            return;
        }

        try {
            if (dexPathInited.compareAndSet(false, true)) {
                try {
                    String fixedAppDataDir = XposedCompat.getCacheDir().getAbsolutePath();
                    dexDir = new File(fixedAppDataDir, "/sandxposed/");
                    if (!dexDir.exists())
                        dexDir.mkdirs();
                } catch (Throwable throwable) {
                    DexLog.e("error when init dex path", throwable);
                }
            }
            Trace.beginSection("SandHook-Xposed");
            long timeStart = System.currentTimeMillis();
            HookMethodEntity stub = null;
            HookMaker hookMaker = null;
            if (XposedCompat.useInternalStub && !HookBlackList.canNotHookByStub(originMethod) && !HookBlackList.canNotHookByBridge(originMethod)) {
                stub = HookStubManager.getHookMethodEntity(originMethod, additionalHookInfo);
            }
            if (stub != null) {
                SandHook.hook(new HookWrapper.HookEntity(originMethod, stub.hook, stub.backup, false));
                entityMap.put(originMethod, stub);
            } else {
                if (HookBlackList.canNotHookByBridge(originMethod)) {
                    hookMaker = new HookerDexMaker();
                } else {
                    hookMaker = defaultHookMaker;
                }
                hookMaker.start(originMethod, additionalHookInfo,
                        new ProxyClassLoader(DynamicBridge.class.getClassLoader(), originMethod.getDeclaringClass().getClassLoader()), dexDir == null ? null : dexDir.getAbsolutePath());
                hookedInfo.put(originMethod, hookMaker.getCallBackupMethod());
            }
            DexLog.d("hook method <" + originMethod + "> cost " + (System.currentTimeMillis() - timeStart) + " ms, by " + (stub != null ? ("internal stub:" + stub.hook) : hookMaker.getClass().getSimpleName()));
            Trace.endSection();
        } catch (Throwable e) {
            DexLog.e("error occur when hook method <" + originMethod.toString() + ">", e);
        }
    }

    public static void clearOatFile() {
        String fixedAppDataDir = XposedCompat.getCacheDir().getAbsolutePath();
        File dexOatDir = new File(fixedAppDataDir, "/sandxposed/oat/");
        if (!dexOatDir.exists())
            return;
        try {
            FileUtils.delete(dexOatDir);
            dexOatDir.mkdirs();
        } catch (Throwable throwable) {
        }
    }

    private static boolean checkMember(Member member) {

        if (member instanceof Method) {
            return true;
        } else if (member instanceof Constructor<?>) {
            return true;
        } else if (member.getDeclaringClass().isInterface()) {
            DexLog.e("Cannot hook interfaces: " + member.toString());
            return false;
        } else if (Modifier.isAbstract(member.getModifiers())) {
            DexLog.e("Cannot hook abstract methods: " + member.toString());
            return false;
        } else {
            DexLog.e("Only methods and constructors can be hooked: " + member.toString());
            return false;
        }
    }
}


