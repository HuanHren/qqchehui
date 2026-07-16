package com.huanhren.qqantirevoke;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private SharedPreferences preferences;
    private EditText grayTemplateInput;
    private TextView logStatus;
    private TextView logView;
    private String displayedLogs = "";

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

        root.addView(text("QQ 防撤回 · NT v3.3", 25, true));
        root.addView(text(
                "目标环境：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n"
                        + "v3.3 修复主动语音转发菜单、QQ 原生设置入口、专属日志和前台加载提示。",
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
        root.addView(option(
                "详细诊断日志",
                ModulePrefs.KEY_DIAGNOSTICS,
                ModulePrefs.DEFAULT_DIAGNOSTICS
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

        root.addView(section("模块专属日志"));
        root.addView(text(
                "QQ 进程会通过显式广播把 [QQAntiRevoke] 日志直接保存到本 App。"
                        + "这里不会混入其他 LSPosed 模块，也不再需要 Root 权限。",
                14,
                false
        ));

        LinearLayout logActions = row();
        logActions.addView(button("刷新日志", this::refreshLogs), weighted());
        logActions.addView(button("复制", this::copyLogs), weighted());
        root.addView(logActions);

        LinearLayout logActions2 = row();
        logActions2.addView(button("写入自检", this::writeSelfTest), weighted());
        logActions2.addView(button("清空日志", this::clearLogs), weighted());
        root.addView(logActions2);

        logStatus = text("尚未读取日志", 13, false);
        logStatus.setPadding(0, dp(10), 0, dp(8));
        root.addView(logStatus);

        logView = text("", 12, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMinHeight(dp(320));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundResource(android.R.drawable.edit_text);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text(
                "\n测试顺序：\n"
                        + "1. 覆盖安装 v3.3，并在 LSPosed 中保持 QQ 作用域启用。\n"
                        + "2. 强制停止并重新打开 QQ。\n"
                        + "3. QQ 前台应显示“v3.3 已加载”。\n"
                        + "4. QQ 设置页应出现“QQ 防撤回 NT”。\n"
                        + "5. 长按已播放过的语音，应出现模块主动添加的“转发”。\n"
                        + "6. 回到本 App 点击刷新日志。",
                14,
                false
        ));

        setContentView(scroll);
        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (logView != null) {
            refreshLogs();
        }
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
    }

    private void refreshLogs() {
        Bundle result = callProvider(ModulePrefs.LOG_METHOD_READ, null);
        if (result == null || !result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            String reason = result == null
                    ? "日志 Provider 未返回结果"
                    : result.getString(ModulePrefs.LOG_RESULT_ERROR, "未知错误");
            showLogs("读取失败", reason);
            return;
        }
        String logs = result.getString(ModulePrefs.LOG_RESULT_TEXT, "").trim();
        if (logs.isEmpty()) {
            showLogs("暂无日志", "先点击“写入自检”。然后强制停止并重新打开 QQ，再返回刷新。");
        } else {
            int count = logs.split("\\R").length;
            showLogs("已记录 " + count + " 行 QQAntiRevoke 日志", logs);
        }
    }

    private void writeSelfTest() {
        Bundle extras = new Bundle();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        extras.putString(
                ModulePrefs.LOG_EXTRA_LINE,
                "[QQAntiRevoke] v3.3 App 专属日志自检成功，time=" + time
        );
        Bundle result = callProvider(ModulePrefs.LOG_METHOD_APPEND, extras);
        if (result != null && result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            Toast.makeText(this, "日志存储自检成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "日志存储自检失败", Toast.LENGTH_LONG).show();
        }
        refreshLogs();
    }

    private void clearLogs() {
        Bundle result = callProvider(ModulePrefs.LOG_METHOD_CLEAR, null);
        if (result != null && result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            showLogs("日志已清空", "");
        } else {
            Toast.makeText(this, "清空日志失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyLogs() {
        if (displayedLogs.trim().isEmpty()) {
            Toast.makeText(this, "没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("QQAntiRevoke v3.3 日志", displayedLogs));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private Bundle callProvider(String method, Bundle extras) {
        try {
            return getContentResolver().call(ModulePrefs.LOG_URI, method, null, extras);
        } catch (Throwable throwable) {
            Bundle error = new Bundle();
            error.putBoolean(ModulePrefs.LOG_RESULT_OK, false);
            error.putString(ModulePrefs.LOG_RESULT_ERROR, throwable.toString());
            return error;
        }
    }

    private void showLogs(String status, String logs) {
        displayedLogs = logs == null ? "" : logs;
        logStatus.setText(status);
        logView.setText(displayedLogs);
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
