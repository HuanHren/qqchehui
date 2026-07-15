package com.huanhren.qqantirevoke.hook;

import de.robv.android.xposed.XposedBridge;

final class HookLog {
    private static final String PREFIX = "[QQAntiRevoke] ";
    private static volatile boolean diagnostics = true;

    private HookLog() {}

    static void setDiagnostics(boolean enabled) {
        diagnostics = enabled;
    }

    static void info(String message) {
        XposedBridge.log(PREFIX + message);
    }

    static void debug(String message) {
        if (diagnostics) {
            XposedBridge.log(PREFIX + message);
        }
    }

    static void error(String message, Throwable throwable) {
        XposedBridge.log(PREFIX + message + ": " + throwable);
        XposedBridge.log(throwable);
    }
}
