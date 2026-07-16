package com.huanhren.qqantirevoke.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

final class HookLog {
    private static final String PREFIX = "[QQAntiRevoke] ";
    private static final int MAX_PROVIDER_CHARS = 20_000;
    private static final long MAX_FILE_BYTES = 1_500_000L;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QQAntiRevoke-LogWriter");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean diagnostics = true;
    private static volatile Context applicationContext;
    private static volatile String processName = "unknown";
    private static volatile File hostLogFile;

    private HookLog() {}

    static void initialize(Context context, String currentProcessName) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
            try {
                File directory = new File(context.getFilesDir(), ModulePrefs.HOST_LOG_DIRECTORY);
                if (!directory.exists() && !directory.mkdirs()) {
                    XposedBridge.log(PREFIX + "无法创建 QQ 私有日志目录: " + directory);
                }
                boolean msf = currentProcessName != null && currentProcessName.endsWith(":MSF");
                hostLogFile = new File(
                        directory,
                        msf ? ModulePrefs.HOST_LOG_MSF_FILE : ModulePrefs.HOST_LOG_MAIN_FILE
                );
            } catch (Throwable throwable) {
                XposedBridge.log(PREFIX + "初始化 QQ 私有日志文件失败: " + throwable);
            }
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
        writeEverywhere(summary + (stackTrace.isEmpty() ? "" : "\n" + stackTrace));
    }

    private static void publish(String line) {
        XposedBridge.log(line);
        writeEverywhere(line);
    }

    private static void writeEverywhere(String line) {
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
            appendHostFile(payload);
            writeProvider(context, payload);
            sendExplicitBroadcast(context, payload);
        });
    }

    private static void appendHostFile(String payload) {
        File file = hostLogFile;
        if (file == null) {
            return;
        }
        try {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                File old = new File(file.getParentFile(), file.getName() + ".old");
                if (old.exists() && !old.delete()) {
                    XposedBridge.log(PREFIX + "无法删除旧日志: " + old);
                }
                if (!file.renameTo(old)) {
                    try (FileWriter truncate = new FileWriter(file, false)) {
                        truncate.write("");
                    }
                }
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
                writer.write(time);
                writer.write(' ');
                writer.write(payload);
                writer.newLine();
            }
        } catch (Throwable throwable) {
            XposedBridge.log(PREFIX + "写入 QQ 私有日志失败: " + throwable);
        }
    }

    private static void writeProvider(Context context, String payload) {
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
                XposedBridge.log(PREFIX + "Provider 日志写入失败，继续使用广播/文件: " + reason);
            }
        } catch (Throwable throwable) {
            XposedBridge.log(PREFIX + "Provider 日志写入异常，继续使用广播/文件: " + throwable);
        }
    }

    private static void sendExplicitBroadcast(Context context, String payload) {
        try {
            Intent intent = new Intent(ModulePrefs.LOG_BRIDGE_ACTION);
            intent.setComponent(new ComponentName(
                    ModulePrefs.MODULE_PACKAGE,
                    ModulePrefs.LOG_BRIDGE_RECEIVER
            ));
            intent.putExtra(ModulePrefs.LOG_EXTRA_LINE, payload);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            XposedBridge.log(PREFIX + "显式广播日志失败，已保留 LSPosed/文件日志: " + throwable);
        }
    }
}
