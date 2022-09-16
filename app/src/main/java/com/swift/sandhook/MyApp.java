package com.swift.sandhook;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.swift.sandhook.testHookers.ActivityHooker;
import com.swift.sandhook.testHookers.CtrHook;
import com.swift.sandhook.testHookers.LogHooker;
import com.swift.sandhook.wrapper.HookErrorException;
import com.swift.sandhook.xposedcompat.XposedCompat;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class MyApp extends Application {

    static {
        try {
            System.loadLibrary("app");
        } catch (Throwable e) {
            Log.e("sanbo.app", Log.getStackTraceString(e));
        }
    }

    //for test pending hook case
    public volatile static boolean initedTest = false;

    // 测试所有的选项
    public volatile static boolean DEBUG_ALL = false;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            HiddenApiBypass.unseal(this);
            NativeHookTest.hook();
            SandHookConfig.DEBUG = true;
            SandHookConfig.delayHook = false;

            Log.i("SandHook", "current sdk int:" + Build.VERSION.SDK_INT + ",preview sdk int:" + getPreviewSDKInt());
            if (Build.VERSION.SDK_INT == 29 && getPreviewSDKInt() > 0) {
                // Android R preview
                SandHookConfig.SDK_INT = 30;
            }

            SandHook.disableVMInline();
            SandHook.tryDisableProfile(getPackageName());
            SandHook.disableDex2oatInline(false);
            SandHook.forbidUseNterp();

            HiddenApiBypass.unseal(this);

            try {
                SandHook.addHookClass(
                        CtrHook.class,
                        LogHooker.class,
                        ActivityHooker.class);
            } catch (HookErrorException e) {
                e.printStackTrace();
            }

            //for xposed compat(no need xposed comapt new)
            XposedCompat.cacheDir = getCacheDir();

            //for load xp module(sandvxp)
            XposedCompat.context = this;
            XposedCompat.classLoader = getClassLoader();
            XposedCompat.isFirstApplication = true;
            XposedCompat.useInternalStub = true;

            if (DEBUG_ALL) {
                HookPass.init();
            }

        } catch (Throwable e) {
            Log.e("SandHook", "init sandhook test error:", e);
        }
    }

    public static int getPreviewSDKInt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return Build.VERSION.PREVIEW_SDK_INT;
            } catch (Throwable e) {
                // ignore
            }
        }
        return 0;
    }
}
