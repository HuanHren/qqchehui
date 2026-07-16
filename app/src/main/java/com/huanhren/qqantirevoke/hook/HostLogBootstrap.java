package com.huanhren.qqantirevoke.hook;

import android.content.Context;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/** Creates a synchronous probe before the asynchronous log writer starts. */
final class HostLogBootstrap {
    private HostLogBootstrap() {}

    static void writeProbe(Context context, String processName) {
        try {
            File directory = new File(context.getFilesDir(), ModulePrefs.HOST_LOG_DIRECTORY);
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("mkdir failed: " + directory);
            }
            boolean msf = processName != null && processName.endsWith(":MSF");
            File file = new File(directory,
                    msf ? ModulePrefs.HOST_LOG_MSF_FILE : ModulePrefs.HOST_LOG_MAIN_FILE);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
                writer.write(time);
                writer.write(" [QQAntiRevoke] [process=");
                writer.write(processName == null ? "unknown" : processName);
                writer.write("] v3.2.1 同步日志探针成功，path=");
                writer.write(file.getAbsolutePath());
                writer.newLine();
            }
            XposedBridge.log("[QQAntiRevoke] v3.2.1 同步日志探针=" + file.getAbsolutePath());
        } catch (Throwable throwable) {
            XposedBridge.log("[QQAntiRevoke] v3.2.1 同步日志探针失败: " + throwable);
            XposedBridge.log(throwable);
        }
    }
}
