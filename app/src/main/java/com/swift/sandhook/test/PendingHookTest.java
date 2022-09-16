package com.swift.sandhook.test;

import com.swift.sandhook.App;

public class PendingHookTest {

    static {
        if (!App.initedTest) {
            throw new RuntimeException("PendingHookTest.class may can not init this time!");
        }
    }

    public static boolean test() {
        return false;
    }

}
