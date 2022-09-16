package com.swift.sandhook.test;

import com.swift.sandhook.MyApp;

public class PendingHookTest {

    static {
        if (!MyApp.initedTest) {
            throw new RuntimeException("PendingHookTest.class may can not init this time!");
        }
    }

    public static boolean test() {
        return false;
    }

}
