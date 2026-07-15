package com.huanhren.qqantirevoke;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ModulePrefs.ACTION_MODULE_LOG.equals(intent.getAction())) return;

        String message = intent.getStringExtra(ModulePrefs.EXTRA_LOG_MESSAGE);
        ModuleLogStore.append(context, message);
    }
}
