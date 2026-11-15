package de.robv.android.xposed;

/**
 * 编译期桩：仅用于让模块源码通过 javac，不会打进 dex。
 * 运行时由 LSPosed 提供真实实现。
 */
public class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member hookMethod,
                                                  XC_MethodHook callback) {
        return null;
    }
}
