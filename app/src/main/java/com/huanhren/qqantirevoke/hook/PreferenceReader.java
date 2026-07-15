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
            return new Settings(
                    preferences.getBoolean(ModulePrefs.KEY_ENABLED, ModulePrefs.DEFAULT_ENABLED),
                    preferences.getBoolean(ModulePrefs.KEY_AGGRESSIVE, ModulePrefs.DEFAULT_AGGRESSIVE),
                    preferences.getBoolean(ModulePrefs.KEY_DIAGNOSTICS, ModulePrefs.DEFAULT_DIAGNOSTICS)
            );
        } catch (Throwable throwable) {
            HookLog.error("读取模块设置失败，将使用默认值", throwable);
            return Settings.defaults();
        }
    }

    static final class Settings {
        private final boolean enabled;
        private final boolean aggressive;
        private final boolean diagnostics;

        Settings(boolean enabled, boolean aggressive, boolean diagnostics) {
            this.enabled = enabled;
            this.aggressive = aggressive;
            this.diagnostics = diagnostics;
        }

        boolean enabled() { return enabled; }
        boolean aggressive() { return aggressive; }
        boolean diagnostics() { return diagnostics; }

        static Settings defaults() {
            return new Settings(
                    ModulePrefs.DEFAULT_ENABLED,
                    ModulePrefs.DEFAULT_AGGRESSIVE,
                    ModulePrefs.DEFAULT_DIAGNOSTICS
            );
        }
    }
}
