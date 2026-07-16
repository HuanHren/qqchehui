package com.huanhren.qqantirevoke.hook;

import android.content.Context;

import de.robv.android.xposed.XposedBridge;

/**
 * v3.4 no longer exposes or persists module logs.
 *
 * <p>Normal diagnostic messages are intentionally discarded. Only unexpected errors are sent to
 * LSPosed so a crash does not become completely silent; no files, broadcasts, providers or App log
 * screens are used.</p>
 */
final class HookLog {
    private static final String PREFIX = "[QQAntiRevoke] ";

    private HookLog() {}

    static void initialize(Context context, String currentProcessName) {
        // Logging storage was removed in v3.4.
    }

    static void setDiagnostics(boolean enabled) {
        // Detailed diagnostics were removed in v3.4.
    }

    static void info(String message) {
        // Intentionally ignored.
    }

    static void debug(String message) {
        // Intentionally ignored.
    }

    static void error(String message, Throwable throwable) {
        XposedBridge.log(PREFIX + message + (throwable == null ? "" : ": " + throwable));
        if (throwable != null) {
            XposedBridge.log(throwable);
        }
    }
}
