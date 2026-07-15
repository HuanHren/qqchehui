package com.huanhren.qqantirevoke;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private SharedPreferences preferences;
    private TextView logStatus;
    private TextView logView;

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
        root.addView(text(
                "适配目标：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n"
                        + "模块让 QQ 的撤回流程继续运行以显示小灰条，只尝试阻止原消息从本地消息列表中被移除。",
                16,
                false
        ));

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

        root.addView(text("\n模块专属日志", 21, true));
        root.addView(text(
                "这里只显示本模块产生的 [QQAntiRevoke] 日志，不会混入其他 LSPosed 模块。"
                        + "安装此版本后，需要强制停止并重新打开 QQ，日志才会开始写入。",
                14,
                false
        ));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(actionButton("刷新", this::refreshLogs), weightedButtonParams());
        actions.addView(actionButton("复制", this::copyLogs), weightedButtonParams());
        actions.addView(actionButton("清空", this::clearLogs), weightedButtonParams());
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        logStatus = text("", 13, false);
        logStatus.setPadding(0, dp(10), 0, dp(8));
        root.addView(logStatus);

        logView = text("", 12, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMinHeight(dp(260));
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackgroundResource(android.R.drawable.edit_text);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text(
                "\n使用步骤：\n"
                        + "1. 在 LSPosed 中启用本模块。\n"
                        + "2. 作用域只勾选 QQ。\n"
                        + "3. 强制停止并重新打开 QQ。\n"
                        + "4. 测试一次撤回后返回本页面，点击“刷新”。\n\n"
                        + "QQ 更新后混淆方法可能变化，请先使用不重要的消息测试。",
                15,
                false
        ));

        setContentView(scroll);
        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (logView != null) refreshLogs();
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

    private Button actionButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMarginEnd(dp(6));
        return params;
    }

    private void refreshLogs() {
        String logs = ModuleLogStore.readAll(this);
        if (logs.isEmpty()) {
            logStatus.setText("暂无模块专属日志");
            logView.setText(
                    "请确认已安装最新 APK，然后强制停止并重新打开 QQ。\n"
                            + "看到消息后测试一次撤回，再回到这里点击“刷新”。"
            );
            return;
        }

        int lines = logs.split("\\R").length;
        logStatus.setText("已记录 " + lines + " 行，仅包含 QQAntiRevoke 日志");
        logView.setText(logs);
    }

    private void copyLogs() {
        String logs = ModuleLogStore.readAll(this);
        if (logs.isEmpty()) {
            Toast.makeText(this, "目前没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("QQAntiRevoke 日志", logs));
            Toast.makeText(this, "模块日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogs() {
        ModuleLogStore.clear(this);
        refreshLogs();
        Toast.makeText(this, "模块日志已清空", Toast.LENGTH_SHORT).show();
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
