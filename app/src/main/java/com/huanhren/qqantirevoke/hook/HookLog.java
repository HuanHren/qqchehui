package com.huanhren.qqantirevoke.hook;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.huanhren.qqantirevoke.ModulePrefs;

import de.robv.android.xposed.XposedBridge;

final class HookLog {
    private static final String PREFIX = "[QQAntiRevoke] ";
    private static final int MAX_BROADCAST_CHARS = 20_000;

    private static volatile boolean diagnostics = true;
    private static volatile Context applicationContext;

    private HookLog() {}

    static void initialize(Context context) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
    }

    static void setDiagnostics(boolean enabled) {
        diagnostics = enabled;
    }

    static void info(String message) {
        publish(PREFIX + message);
    }

    static void debug(String message) {
        if (diagnostics) {
            publish(PREFIX + message);
        }
    }

    static void error(String message, Throwable throwable) {
        String summary = PREFIX + message + ": " + throwable;
        XposedBridge.log(summary);
        XposedBridge.log(throwable);

        String stackTrace = throwable == null ? "" : Log.getStackTraceString(throwable);
        broadcast(summary + (stackTrace.isEmpty() ? "" : "\n" + stackTrace));
    }

    private static void publish(String line) {
        XposedBridge.log(line);
        broadcast(line);
    }

    private static void broadcast(String line) {
        Context context = applicationContext;
        if (context == null || line == null) return;

        String payload = line.length() > MAX_BROADCAST_CHARS
                ? line.substring(0, MAX_BROADCAST_CHARS) + "\n…日志过长，已截断"
                : line;

        try {
            Intent intent = new Intent(ModulePrefs.ACTION_MODULE_LOG);
            intent.setClassName(ModulePrefs.MODULE_PACKAGE, ModulePrefs.LOG_RECEIVER_CLASS);
            intent.putExtra(ModulePrefs.EXTRA_LOG_MESSAGE, payload);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            // 不通过 error() 记录，避免广播失败时递归。
            XposedBridge.log(PREFIX + "发送模块专属日志失败: " + throwable);
        }
    }
}
