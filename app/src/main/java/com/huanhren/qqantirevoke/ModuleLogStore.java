package com.huanhren.qqantirevoke;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ModuleLogStore {
    private static final String CURRENT_FILE = "qq_antirevoke.log";
    private static final String OLD_FILE = "qq_antirevoke.old.log";
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_MESSAGE_CHARS = 20_000;

    private ModuleLogStore() {}

    public static synchronized void append(Context context, String message) {
        if (context == null || message == null) return;

        String normalized = message.trim();
        if (normalized.isEmpty() || !normalized.startsWith("[QQAntiRevoke]")) return;
        if (normalized.length() > MAX_MESSAGE_CHARS) {
            normalized = normalized.substring(0, MAX_MESSAGE_CHARS) + "\n…日志过长，已截断";
        }

        Context appContext = context.getApplicationContext();
        File current = new File(appContext.getFilesDir(), CURRENT_FILE);
        rotateIfNeeded(appContext, current);

        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
        ).format(new Date());
        String entry = timestamp + "  " + normalized + "\n";

        try (FileOutputStream output = new FileOutputStream(current, true)) {
            output.write(entry.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Throwable ignored) {
            // 日志记录失败不能影响 QQ 或模块设置界面。
        }
    }

    public static synchronized String readAll(Context context) {
        Context appContext = context.getApplicationContext();
        StringBuilder result = new StringBuilder();
        appendFile(result, new File(appContext.getFilesDir(), OLD_FILE));
        appendFile(result, new File(appContext.getFilesDir(), CURRENT_FILE));
        return result.toString().trim();
    }

    public static synchronized void clear(Context context) {
        Context appContext = context.getApplicationContext();
        deleteQuietly(new File(appContext.getFilesDir(), CURRENT_FILE));
        deleteQuietly(new File(appContext.getFilesDir(), OLD_FILE));
    }

    private static void rotateIfNeeded(Context context, File current) {
        if (!current.exists() || current.length() < MAX_FILE_BYTES) return;

        File old = new File(context.getFilesDir(), OLD_FILE);
        deleteQuietly(old);
        if (!current.renameTo(old)) {
            deleteQuietly(current);
        }
    }

    private static void appendFile(StringBuilder target, File file) {
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.append(line).append('\n');
            }
        } catch (Throwable ignored) {
            // 读取失败时显示现有可读部分即可。
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {
            // 清理失败不影响主流程。
        }
    }
}
