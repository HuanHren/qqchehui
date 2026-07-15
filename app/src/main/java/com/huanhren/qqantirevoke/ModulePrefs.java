package com.huanhren.qqantirevoke;

public final class ModulePrefs {
    public static final String MODULE_PACKAGE = "com.huanhren.qqantirevoke";
    public static final String PREF_FILE = "settings";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_AGGRESSIVE = "aggressive_compatibility";
    public static final String KEY_DIAGNOSTICS = "diagnostics";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_AGGRESSIVE = true;
    public static final boolean DEFAULT_DIAGNOSTICS = true;

    public static final String ACTION_MODULE_LOG = MODULE_PACKAGE + ".ACTION_MODULE_LOG";
    public static final String EXTRA_LOG_MESSAGE = "log_message";
    public static final String LOG_RECEIVER_CLASS = MODULE_PACKAGE + ".LogReceiver";

    private ModulePrefs() {}
}
