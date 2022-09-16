package com.swift.sandhook.test;

import com.swift.sandhook.DLog;

public class XTt {

    public XTt() {

    }

    public static String testStaticValueForTest() {
        DLog.d("testStaticValueForTest");
        return "testStaticValueForTest";
    }

    public static String testStaticValueForTest(String a1, int i2, Class<?> c3) {
        DLog.d("testStaticValueForTest");
        return "testStaticValueForTest(String,int,Class)";
    }


    public String printValueForTest() {
        DLog.d("printValueForTest");
        return "printValueForTest";
    }

    public static String printValueForTest(String a1, int i2, Class<?> c3) {
        DLog.d("printValueForTest");
        return "printValueForTest(String,int,Class)";
    }

    @Override
    public String toString() {
        return "" + hashCode();
    }
}
