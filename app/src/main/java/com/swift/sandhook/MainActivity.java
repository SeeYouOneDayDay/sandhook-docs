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
//        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);
//
//        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
//        fab.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                        .setAction("Action", null).show();
//            }
//        });

        StringBuilder hookTestResult = new StringBuilder();
        hookTestResult.append("当前安卓版本：").append(Build.VERSION.SDK_INT).append(":").append(Build.VERSION.PREVIEW_SDK_INT).append("\r\n");
        hookTestResult.append("静态方法Hook：").append(HookPass.getStaticMethodHookResult()).append("\r\n");
        hookTestResult.append("JNI方法Hook：").append(HookPass.getJniMethodHookResult()).append("\r\n");
        hookTestResult.append("App实例方法Hook：").append(HookPass.getAppMethodHookResult()).append("\r\n");
        hookTestResult.append("系统类实例方法Hook：").append(HookPass.getSystemMethodHookResult()).append("\r\n");
        hookTestResult.append("APP类构造方法Hook：").append(HookPass.getAppConstructorHookResult()).append("\r\n");
        hookTestResult.append("系统类构造方法Hook：").append(HookPass.getSystemConstructorHookResult()).append("\r\n");
        hookTestResult.append("实例方法Inline模式Hook：").append(HookPass.getInstanceMethodInlineResult()).append("\r\n");
        hookTestResult.append("实例方法Replace模式Hook：").append(HookPass.getInstanceMethodReplaceResult()).append("\r\n");
//        TextView tv = (TextView) findViewById(R.id.sample_text);
        tv.setText(hookTestResult);
    }

//    @Override
//    public boolean onCreateOptionsMenu(Menu menu) {
//        // Inflate the menu; this adds items to the action bar if it is present.
//        getMenuInflater().inflate(R.menu.menu_main, menu);
//        return true;
//    }

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

