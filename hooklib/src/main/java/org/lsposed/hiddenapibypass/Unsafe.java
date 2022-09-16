/*
 * Copyright 2014-2015 Marvin Wißfeld
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lsposed.hiddenapibypass;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

//http://mishadoff.com/blog/java-magic-part-4-sun-dot-misc-dot-unsafe/
//http://aospxref.com/android-7.1.2_r39/xref/libcore/ojluni/src/main/java/sun/misc/Unsafe.java
//http://aospxref.com/android-8.1.0_r81/xref/libcore/ojluni/src/main/java/sun/misc/Unsafe.java
//http://aospxref.com/android-9.0.0_r61/xref/libcore/ojluni/src/main/java/sun/misc/Unsafe.java
//http://aospxref.com/android-10.0.0_r47/xref/libcore/ojluni/src/main/java/sun/misc/Unsafe.java
//http://aospxref.com/android-11.0.0_r21/xref/libcore/ojluni/src/main/java/sun/misc/Unsafe.java
//http://aospxref.com/android-12.0.0_r3/xref/libcore/ojluni/src/main/java/sun/misc/Unsafe.java
public final class Unsafe {

    private static Object unsafe;
    private static Class unsafeClass;

    private Unsafe() {
    }

    static {
        try {
            if (unsafeClass == null) {
                unsafeClass = Class.forName("sun.misc.Unsafe");
            }
            //private static final Unsafe theUnsafe = THE_ONE;
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
        } catch (Throwable e) {
        }
        if (unsafe == null) {
            try {
                //private static final Unsafe THE_ONE = new Unsafe();
                final Field theUnsafe = unsafeClass.getDeclaredField("THE_ONE");
                theUnsafe.setAccessible(true);
                unsafe = theUnsafe.get(null);
            } catch (Throwable e2) {
            }
        }
        if (unsafe == null) {
            try {
                //public static Unsafe getUnsafe()
                final Method getUnsafe = unsafeClass.getDeclaredMethod("getUnsafe");
                getUnsafe.setAccessible(true);
                unsafe = getUnsafe.invoke(null);
            } catch (Throwable e3) {
            }
        }
        if (unsafe == null) {
            try {
                Class AbstractQueuedSynchronizer = Class.forName("java.util.concurrent.locks.AbstractQueuedSynchronizer");
                Field unsafeField = null;
                if (Build.VERSION.SDK_INT < 24) {
                    // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r9:libcore/luni/src/main/java/java/util/concurrent/locks/AbstractQueuedSynchronizer.java;l=2239
                    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r44:libcore/ojluni/src/main/java/java/util/concurrent/locks/AbstractQueuedSynchronizer.java;l=527
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r18:libcore/ojluni/src/main/java/java/util/concurrent/locks/AbstractQueuedSynchronizer.java;l=527
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r34:libcore/ojluni/src/main/java/java/util/concurrent/locks/AbstractQueuedSynchronizer.java;l=527
                    unsafeField = AbstractQueuedSynchronizer.getDeclaredField("unsafe");
                } else {
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r1:libcore/luni/src/main/java/java/util/concurrent/locks/AbstractQueuedSynchronizer.java;l=499
                    unsafeField = AbstractQueuedSynchronizer.getDeclaredField("U");
                }
                // 13bate 版本失效了
                // https://cs.android.com/android/platform/superproject/+/android-t-preview-2:libcore/ojluni/src/main/java/java/util/concurrent/locks/AbstractOwnableSynchronizer.java
                // 但是可考虑 LockSupport.java unsafe/U来获取，获取对象转变
                if (unsafeField != null) {
                    unsafeField.setAccessible(true);
                    unsafe = unsafeField.get(null);
                }

            } catch (Throwable e) {
            }
        }
    }


    public static long objectFieldOffset(Field field) {
        if (unsafe == null || unsafeClass == null) {
            return 0;
        }
        try {
            // android 4有接口:  private static native long objectFieldOffset0(Field field);
            //所有版本均有接口：public long objectFieldOffset(Field field)
            return (long) unsafeClass.getDeclaredMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
        } catch (Throwable e) {
        }
        return 0;
    }


    public static long getLong(Object array, long offset) {
        if (unsafe == null || unsafeClass == null) {
            return 0;
        }
        try {
            // public native long getLongVolatile(Object obj, long offset);
            return (long) unsafeClass.getDeclaredMethod("getLongVolatile", Object.class, long.class).invoke(unsafe, array, offset);
        } catch (Throwable e) {
            try {
                //public native long getLong(Object obj, long offset);
                return (long) unsafeClass.getDeclaredMethod("getLong", Object.class, long.class).invoke(unsafe, array, offset);
            } catch (Throwable e1) {
            }
        }
        return 0;
    }


    public static void putLong(Object array, long offset, long value) {
        if (unsafe == null || unsafeClass == null) {
            return;
        }
        try {
            // public native void putLongVolatile(Object obj, long offset, long newValue);
            unsafeClass.getDeclaredMethod("putLongVolatile", Object.class, long.class, long.class).invoke(unsafe, array, offset, value);
        } catch (Throwable e) {
            try {
                //public native void putLong(Object obj, long offset, long newValue);
                unsafeClass.getDeclaredMethod("putLong", Object.class, long.class, long.class).invoke(unsafe, array, offset, value);
            } catch (Throwable e1) {
            }
        }
    }

    public static int getInt(long offset) {
        if (unsafe == null || unsafeClass == null) {
            return 0;
        }
        try {
            //public native int getInt(long address);
            return (int) unsafeClass.getDeclaredMethod("getInt", long.class).invoke(unsafe, offset);
        } catch (Throwable e1) {
        }
        return 0;
    }


    public static Object getObject(Object obj, long offset) {
        if (unsafe == null || unsafeClass == null) {
            return null;
        }
        try {
            //   public native Object getObjectVolatile(Object obj, long offset);
            return unsafeClass.getDeclaredMethod("getObjectVolatile", Object.class, long.class).invoke(unsafe, obj, offset);
        } catch (Throwable e) {
            try {
                // public native Object getObject(Object obj, long offset)
                return unsafeClass.getDeclaredMethod("getObject", Object.class, long.class).invoke(unsafe, obj, offset);
            } catch (Throwable e1) {
            }
        }
        return null;
    }


    public static void putObject(Object obj, long offset, Object newValue) {
        if (unsafe == null || unsafeClass == null) {
            return;
        }
        try {
            // public native void putObjectVolatile(Object obj, long offset, Object newValue);
            unsafeClass.getDeclaredMethod("putLongVolatile", Object.class, long.class, Object.class).invoke(unsafe, obj, offset, newValue);
        } catch (Throwable e) {
            try {
                // public native void putObject(Object obj, long offset, Object newValue);
                unsafeClass.getDeclaredMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, obj, offset, newValue);
            } catch (Throwable e1) {
            }
        }
    }


}
