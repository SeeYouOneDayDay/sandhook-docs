package com.swift.sandhook;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.swift.sandhook.test.Inter;

public class MainActivity extends Activity {

    public static final String TAG = "SandHookTest";

    Inter inter;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");

        TextView tv = new TextView(this);

        setContentView(tv);

        StringBuilder hookTestResult = new StringBuilder();
        hookTestResult.append("当前安卓版本：").append(Build.VERSION.SDK_INT).append(":").append(Build.VERSION.PREVIEW_SDK_INT);
        if (MyApp.DEBUG_ALL) {
            hookTestResult.append("\r\n静态方法Hook：").append(HookPass.getStaticMethodHookResult())
                    .append("\r\nJNI方法Hook：").append(HookPass.getJniMethodHookResult())
                    .append("\r\nApp实例方法Hook：").append(HookPass.getAppMethodHookResult())
                    .append("\r\n系统类实例方法Hook：").append(HookPass.getSystemMethodHookResult())
                    .append("\r\nAPP类构造方法Hook：").append(HookPass.getAppConstructorHookResult())
                    .append("\r\n系统类构造方法Hook：").append(HookPass.getSystemConstructorHookResult())
                    .append("\r\n实例方法Inline模式Hook：").append(HookPass.getInstanceMethodInlineResult())
                    .append("\r\n实例方法Replace模式Hook：").append(HookPass.getInstanceMethodReplaceResult());
        }

        tv.setText(hookTestResult);
    }


    @Override
    protected void onPause() {
        super.onPause();
        inter = new Inter() {
            @Override
            public void dosth() {
                Log.e("dosth", hashCode() + "");
            }
        };
    }
}

