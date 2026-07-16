package com.huanhren.qqantirevoke.hook;

import com.huanhren.qqantirevoke.ModulePrefs;

import de.robv.android.xposed.XSharedPreferences;

final class PreferenceReader {
    private final XSharedPreferences preferences;

    PreferenceReader() {
        XSharedPreferences loaded;
        try {
            loaded = new XSharedPreferences(ModulePrefs.MODULE_PACKAGE, ModulePrefs.PREF_FILE);
            loaded.makeWorldReadable();
            loaded.reload();
        } catch (Throwable throwable) {
            HookLog.error("无法初始化模块设置，将使用默认值", throwable);
            loaded = null;
        }
        preferences = loaded;
    }

    Settings read() {
        if (preferences == null) {
            return Settings.defaults();
        }
        try {
            preferences.reload();
            String template = preferences.getString(
                    ModulePrefs.KEY_GRAY_TIP_TEMPLATE,
                    ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE
            );
            if (template == null || template.trim().isEmpty()) {
                template = ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE;
            }
            if (template.length() > ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH) {
                template = template.substring(0, ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH);
            }
            return new Settings(
                    preferences.getBoolean(ModulePrefs.KEY_ENABLED, ModulePrefs.DEFAULT_ENABLED),
                    preferences.getBoolean(ModulePrefs.KEY_BLOCK_ONLINE_RECALL, ModulePrefs.DEFAULT_BLOCK_ONLINE_RECALL),
                    preferences.getBoolean(ModulePrefs.KEY_STRIP_SYNC_RECALL, ModulePrefs.DEFAULT_STRIP_SYNC_RECALL),
                    preferences.getBoolean(ModulePrefs.KEY_LEGACY_FALLBACK, ModulePrefs.DEFAULT_LEGACY_FALLBACK),
                    preferences.getBoolean(ModulePrefs.KEY_SHOW_GRAY_TIP, ModulePrefs.DEFAULT_SHOW_GRAY_TIP),
                    template,
                    preferences.getBoolean(ModulePrefs.KEY_QQ_SETTINGS_ENTRY, ModulePrefs.DEFAULT_QQ_SETTINGS_ENTRY),
                    preferences.getBoolean(ModulePrefs.KEY_PTT_FORWARD, ModulePrefs.DEFAULT_PTT_FORWARD),
                    preferences.getBoolean(ModulePrefs.KEY_STARTUP_TOAST, ModulePrefs.DEFAULT_STARTUP_TOAST),
                    preferences.getBoolean(ModulePrefs.KEY_DIAGNOSTICS, ModulePrefs.DEFAULT_DIAGNOSTICS)
            );
        } catch (Throwable throwable) {
            HookLog.error("读取模块设置失败，将使用默认值", throwable);
            return Settings.defaults();
        }
    }

    static final class Settings {
        private final boolean enabled;
        private final boolean blockOnlineRecall;
        private final boolean stripSyncRecall;
        private final boolean legacyFallback;
        private final boolean showGrayTip;
        private final String grayTipTemplate;
        private final boolean qqSettingsEntry;
        private final boolean pttForward;
        private final boolean startupToast;
        private final boolean diagnostics;

        Settings(boolean enabled, boolean blockOnlineRecall, boolean stripSyncRecall,
                boolean legacyFallback, boolean showGrayTip, String grayTipTemplate,
                boolean qqSettingsEntry, boolean pttForward, boolean startupToast,
                boolean diagnostics) {
            this.enabled = enabled;
            this.blockOnlineRecall = blockOnlineRecall;
            this.stripSyncRecall = stripSyncRecall;
            this.legacyFallback = legacyFallback;
            this.showGrayTip = showGrayTip;
            this.grayTipTemplate = grayTipTemplate;
            this.qqSettingsEntry = qqSettingsEntry;
            this.pttForward = pttForward;
            this.startupToast = startupToast;
            this.diagnostics = diagnostics;
        }

        boolean enabled() { return enabled; }
        boolean blockOnlineRecall() { return blockOnlineRecall; }
        boolean stripSyncRecall() { return stripSyncRecall; }
        boolean legacyFallback() { return legacyFallback; }
        boolean showGrayTip() { return showGrayTip; }
        String grayTipTemplate() { return grayTipTemplate; }
        boolean qqSettingsEntry() { return qqSettingsEntry; }
        boolean pttForward() { return pttForward; }
        boolean startupToast() { return startupToast; }
        boolean diagnostics() { return diagnostics; }

        static Settings defaults() {
            return new Settings(
                    ModulePrefs.DEFAULT_ENABLED,
                    ModulePrefs.DEFAULT_BLOCK_ONLINE_RECALL,
                    ModulePrefs.DEFAULT_STRIP_SYNC_RECALL,
                    ModulePrefs.DEFAULT_LEGACY_FALLBACK,
                    ModulePrefs.DEFAULT_SHOW_GRAY_TIP,
                    ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE,
                    ModulePrefs.DEFAULT_QQ_SETTINGS_ENTRY,
                    ModulePrefs.DEFAULT_PTT_FORWARD,
                    ModulePrefs.DEFAULT_STARTUP_TOAST,
                    ModulePrefs.DEFAULT_DIAGNOSTICS
            );
        }
    }
}
