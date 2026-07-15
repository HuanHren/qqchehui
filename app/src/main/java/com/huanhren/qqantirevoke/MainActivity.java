package com.huanhren.qqantirevoke;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = openPreferences();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("QQ 防撤回 · LSPosed", 26, true);
        root.addView(title);
        root.addView(text("适配目标：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n模块让 QQ 的撤回流程继续运行以显示小灰条，只尝试阻止原消息从本地消息列表中被移除。", 16, false));

        root.addView(option(
                "启用防撤回",
                ModulePrefs.KEY_ENABLED,
                ModulePrefs.DEFAULT_ENABLED
        ));
        root.addView(option(
                "兼容模式（推荐开启）",
                ModulePrefs.KEY_AGGRESSIVE,
                ModulePrefs.DEFAULT_AGGRESSIVE
        ));
        root.addView(option(
                "详细诊断日志",
                ModulePrefs.KEY_DIAGNOSTICS,
                ModulePrefs.DEFAULT_DIAGNOSTICS
        ));

        root.addView(text("\n安装后：\n1. 在 LSPosed 中启用本模块。\n2. 作用域只勾选 QQ。\n3. 强制停止并重新打开 QQ。\n4. 在 LSPosed 日志搜索 [QQAntiRevoke]。\n\nQQ 更新后混淆方法可能变化。请先使用不重要的消息测试。", 15, false));
        setContentView(scroll);
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences openPreferences() {
        try {
            return getSharedPreferences(ModulePrefs.PREF_FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return getSharedPreferences(ModulePrefs.PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    private Switch option(String label, String key, boolean defaultValue) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextSize(17f);
        view.setPadding(0, dp(14), 0, dp(14));
        view.setChecked(preferences.getBoolean(key, defaultValue));
        view.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            Toast.makeText(this, "已保存，重启 QQ 后生效", Toast.LENGTH_SHORT).show();
        });
        return view;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 0, 0, dp(12));
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
