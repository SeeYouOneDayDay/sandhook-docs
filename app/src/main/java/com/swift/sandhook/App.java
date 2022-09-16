package com.swift.sandhook;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.swift.sandhook.test.XTt;
import com.swift.sandhook.testHookers.ActivityHooker;
import com.swift.sandhook.testHookers.CtrHook;
import com.swift.sandhook.testHookers.LogHooker;
import com.swift.sandhook.wrapper.HookErrorException;
import com.swift.sandhook.xposedcompat.XposedCompat;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class App extends Application {

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
    public volatile static boolean DEBUG_ALL = true;

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
            } else {
                customTest();
            }

        } catch (Throwable e) {
            Log.e("SandHook", "init sandhook test error:", e);
        }
    }

    private void customTest() {
        //testStaticValueForTest(String a1, int i2, Class<?> c3) {
        XposedHelpers.findAndHookMethod(XTt.class, "testStaticValueForTest"
                , String.class, int.class, Class.class
                , new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                Log.e(LogTags.HOOK_IN, "testStaticValueForTest beforeHookedMethod: " + param.method.getName());
            }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        param.setResult("afterHookedMethod 修改 testStaticValueForTest");
                    }
                });

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
