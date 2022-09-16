package com.swift.sandhook;

import android.util.Log;

import java.lang.reflect.Member;

public class DLog {
    public static final String TAG = "sanbo.s.DLog";


    public static int v(String s) {
        return Log.v(TAG, s);
    }

    public static int i(String s) {
        return Log.i(TAG, s);
    }

    public static int d(String s) {
        return Log.d(TAG, s);
    }

    public static void printMethodHookIn(Member member) {
        if (member != null) {
            Log.d(TAG, "method <" + member.toString() + "> hook in");
        }
    }

    public static void printCallOriginError(Member member) {
        if (member != null) {
            Log.e(TAG, "method <" + member.toString() + "> call origin error!");
        }
    }

    public static int w(String s) {
        return Log.w(TAG, s);
    }

    public static int e(String s) {
        return Log.e(TAG, s);
    }

    public static int e(String s, Throwable t) {
        return Log.e(TAG, s, t);
    }

}
