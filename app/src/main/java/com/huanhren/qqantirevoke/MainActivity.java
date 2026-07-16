package com.huanhren.qqantirevoke;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private SharedPreferences preferences;
    private EditText grayTemplateInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = openPreferences();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text("QQ 防撤回 · NT v3.4", 25, true));
        root.addView(text(
                "目标环境：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n"
                        + "本版专注防撤回、撤回灰条和语音转发，不再提供日志功能。",
                15,
                false
        ));

        root.addView(section("功能"));
        root.addView(option("启用模块", ModulePrefs.KEY_ENABLED, ModulePrefs.DEFAULT_ENABLED));
        root.addView(option(
                "阻断在线撤回推送（推荐）",
                ModulePrefs.KEY_BLOCK_ONLINE_RECALL,
                ModulePrefs.DEFAULT_BLOCK_ONLINE_RECALL
        ));
        root.addView(option(
                "过滤启动/重连时的同步撤回（推荐）",
                ModulePrefs.KEY_STRIP_SYNC_RECALL,
                ModulePrefs.DEFAULT_STRIP_SYNC_RECALL
        ));
        root.addView(option(
                "显示本地撤回灰条",
                ModulePrefs.KEY_SHOW_GRAY_TIP,
                ModulePrefs.DEFAULT_SHOW_GRAY_TIP
        ));
        root.addView(option(
                "在 QQ 设置页显示模块入口",
                ModulePrefs.KEY_QQ_SETTINGS_ENTRY,
                ModulePrefs.DEFAULT_QQ_SETTINGS_ENTRY
        ));
        root.addView(option(
                "允许转发别人发送的语音",
                ModulePrefs.KEY_PTT_FORWARD,
                ModulePrefs.DEFAULT_PTT_FORWARD
        ));
        root.addView(option(
                "QQ 前台打开时显示加载提示",
                ModulePrefs.KEY_STARTUP_TOAST,
                ModulePrefs.DEFAULT_STARTUP_TOAST
        ));
        root.addView(option(
                "启用旧消息链路备用拦截",
                ModulePrefs.KEY_LEGACY_FALLBACK,
                ModulePrefs.DEFAULT_LEGACY_FALLBACK
        ));

        root.addView(section("撤回灰条内容"));
        root.addView(text(
                "可用变量：{operator}、{author}、{seq}、{peer}、{type}、"
                        + "{operator_uid}、{author_uid}。",
                14,
                false
        ));
        grayTemplateInput = new EditText(this);
        grayTemplateInput.setText(preferences.getString(
                ModulePrefs.KEY_GRAY_TIP_TEMPLATE,
                ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE
        ));
        grayTemplateInput.setHint(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE);
        grayTemplateInput.setTextSize(16f);
        grayTemplateInput.setMinLines(2);
        grayTemplateInput.setMaxLines(4);
        grayTemplateInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        grayTemplateInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        grayTemplateInput.setBackgroundResource(android.R.drawable.edit_text);
        root.addView(grayTemplateInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout templateActions = row();
        templateActions.addView(button("保存内容", this::saveTemplate), weighted());
        templateActions.addView(button("恢复默认", this::resetTemplate), weighted());
        root.addView(templateActions);

        root.addView(section("使用说明"));
        root.addView(text(
                "1. 修改开关后强制停止并重新打开 QQ。\n"
                        + "2. QQ 设置页会在“账号与安全”下方显示模块入口。\n"
                        + "3. 已下载的历史语音和新语音都应显示“转发”。\n"
                        + "4. 语音选择联系人后，会使用新版圆角确认界面发送。",
                14,
                false
        ));

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
        view.setTextSize(16f);
        view.setPadding(0, dp(10), 0, dp(10));
        view.setChecked(preferences.getBoolean(key, defaultValue));
        view.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            Toast.makeText(this, "已保存，强制停止并重启 QQ 后生效", Toast.LENGTH_SHORT).show();
        });
        return view;
    }

    private void saveTemplate() {
        String value = grayTemplateInput.getText() == null
                ? ""
                : grayTemplateInput.getText().toString().trim();
        if (value.isEmpty()) {
            value = ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE;
        }
        if (value.length() > ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH) {
            value = value.substring(0, ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH);
        }
        preferences.edit().putString(ModulePrefs.KEY_GRAY_TIP_TEMPLATE, value).apply();
        grayTemplateInput.setText(value);
        grayTemplateInput.setSelection(value.length());
        Toast.makeText(this, "灰条内容已保存", Toast.LENGTH_SHORT).show();
    }

    private void resetTemplate() {
        preferences.edit()
                .putString(ModulePrefs.KEY_GRAY_TIP_TEMPLATE, ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE)
                .apply();
        grayTemplateInput.setText(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE);
        grayTemplateInput.setSelection(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE.length());
        Toast.makeText(this, "已恢复默认内容", Toast.LENGTH_SHORT).show();
    }

    private TextView section(String title) {
        TextView view = text("\n" + title, 21, true);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMarginEnd(dp(6));
        return params;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 0, 0, dp(10));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
