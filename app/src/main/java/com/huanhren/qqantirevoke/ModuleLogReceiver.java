package com.huanhren.qqantirevoke;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Explicit cross-process log bridge used by the QQ host process.
 *
 * <p>The receiver only accepts the module's explicit action and lines with the module prefix.
 * The actual persistence remains inside {@link ModuleLogProvider}.</p>
 */
public final class ModuleLogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !ModulePrefs.LOG_BRIDGE_ACTION.equals(intent.getAction())) {
            return;
        }
        String line = intent.getStringExtra(ModulePrefs.LOG_EXTRA_LINE);
        if (line == null || !line.startsWith("[QQAntiRevoke]")) {
            return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putString(ModulePrefs.LOG_EXTRA_LINE, line);
            context.getContentResolver().call(
                    ModulePrefs.LOG_URI,
                    ModulePrefs.LOG_METHOD_APPEND,
                    null,
                    extras
            );
        } catch (Throwable ignored) {
            // The LSPosed log and QQ-private file remain as independent fallbacks.
        }
    }
}
