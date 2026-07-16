package com.huanhren.qqantirevoke;

import android.net.Uri;

public final class ModulePrefs {
    public static final String MODULE_PACKAGE = "com.huanhren.qqantirevoke";
    public static final String QQ_PACKAGE = "com.tencent.mobileqq";
    public static final String PREF_FILE = "settings";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_BLOCK_ONLINE_RECALL = "block_online_recall";
    public static final String KEY_STRIP_SYNC_RECALL = "strip_sync_recall";
    public static final String KEY_LEGACY_FALLBACK = "legacy_fallback";
    public static final String KEY_SHOW_GRAY_TIP = "show_gray_tip";
    public static final String KEY_GRAY_TIP_TEMPLATE = "gray_tip_template";
    public static final String KEY_QQ_SETTINGS_ENTRY = "qq_settings_entry";
    public static final String KEY_PTT_FORWARD = "ptt_forward";
    public static final String KEY_STARTUP_TOAST = "startup_toast";
    public static final String KEY_DIAGNOSTICS = "diagnostics";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_BLOCK_ONLINE_RECALL = true;
    public static final boolean DEFAULT_STRIP_SYNC_RECALL = true;
    public static final boolean DEFAULT_LEGACY_FALLBACK = true;
    public static final boolean DEFAULT_SHOW_GRAY_TIP = true;
    public static final String DEFAULT_GRAY_TIP_TEMPLATE = "{operator}尝试撤回一条消息";
    public static final boolean DEFAULT_QQ_SETTINGS_ENTRY = true;
    public static final boolean DEFAULT_PTT_FORWARD = true;
    public static final boolean DEFAULT_STARTUP_TOAST = true;
    public static final boolean DEFAULT_DIAGNOSTICS = true;
    public static final int MAX_GRAY_TIP_TEMPLATE_LENGTH = 160;

    public static final String HOST_LOG_DIRECTORY = "qqantirevoke";
    public static final String HOST_LOG_MAIN_FILE = "main.log";
    public static final String HOST_LOG_MSF_FILE = "msf.log";

    public static final String LOG_AUTHORITY = MODULE_PACKAGE + ".logs";
    public static final Uri LOG_URI = Uri.parse("content://" + LOG_AUTHORITY);
    public static final String LOG_METHOD_APPEND = "append";
    public static final String LOG_METHOD_READ = "read";
    public static final String LOG_METHOD_CLEAR = "clear";
    public static final String LOG_EXTRA_LINE = "line";
    public static final String LOG_RESULT_TEXT = "logs";
    public static final String LOG_RESULT_OK = "ok";
    public static final String LOG_RESULT_ERROR = "error";

    private ModulePrefs() {}
}
