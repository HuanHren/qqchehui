package com.huanhren.qqantirevoke.hook;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

final class HookLog {
    private static final String PREFIX = "[QQAntiRevoke] ";
    private static final int MAX_PROVIDER_CHARS = 20_000;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QQAntiRevoke-LogWriter");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean diagnostics = true;
    private static volatile Context applicationContext;
    private static volatile String processName = "unknown";

    private HookLog() {}

    static void initialize(Context context, String currentProcessName) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
        if (currentProcessName != null && !currentProcessName.isEmpty()) {
            processName = currentProcessName;
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
        if (throwable != null) {
            XposedBridge.log(throwable);
        }
        String stackTrace = throwable == null ? "" : Log.getStackTraceString(throwable);
        writeToProvider(summary + (stackTrace.isEmpty() ? "" : "\n" + stackTrace));
    }

    private static void publish(String line) {
        XposedBridge.log(line);
        writeToProvider(line);
    }

    private static void writeToProvider(String line) {
        Context context = applicationContext;
        if (context == null || line == null) {
            return;
        }
        String message = line.startsWith(PREFIX) ? line.substring(PREFIX.length()) : line;
        String enriched = PREFIX + "[process=" + processName + "] " + message;
        String payload = enriched.length() > MAX_PROVIDER_CHARS
                ? enriched.substring(0, MAX_PROVIDER_CHARS) + "\n…日志过长，已截断"
                : enriched;
        WRITER.execute(() -> {
            try {
                Bundle extras = new Bundle();
                extras.putString(ModulePrefs.LOG_EXTRA_LINE, payload);
                Bundle result = context.getContentResolver().call(
                        ModulePrefs.LOG_URI,
                        ModulePrefs.LOG_METHOD_APPEND,
                        null,
                        extras
                );
                if (result == null || !result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
                    String reason = result == null
                            ? "provider returned null"
                            : result.getString(ModulePrefs.LOG_RESULT_ERROR, "unknown error");
                    XposedBridge.log(PREFIX + "模块专属日志写入失败: " + reason);
                }
            } catch (Throwable throwable) {
                XposedBridge.log(PREFIX + "模块专属日志写入异常: " + throwable);
            }
        });
    }
}
