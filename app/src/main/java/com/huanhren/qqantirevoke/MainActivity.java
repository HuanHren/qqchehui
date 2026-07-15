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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text("QQ 防撤回 · NT v3.0", 25, true));
        root.addView(text(
                "目标环境：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n"
                        + "v3.0 不再依赖 k/V/Z 删除链路，而是在 QQ NT 的 onMsfPush 入口识别并阻断撤回推送。",
                15,
                false
        ));

        root.addView(option(
                "启用防撤回",
                ModulePrefs.KEY_ENABLED,
                ModulePrefs.DEFAULT_ENABLED
        ));
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
                "启用旧消息链路备用拦截",
                ModulePrefs.KEY_LEGACY_FALLBACK,
                ModulePrefs.DEFAULT_LEGACY_FALLBACK
        ));
        root.addView(option(
                "详细诊断日志",
                ModulePrefs.KEY_DIAGNOSTICS,
                ModulePrefs.DEFAULT_DIAGNOSTICS
        ));

        root.addView(text("\n模块专属日志", 21, true));
        root.addView(text(
                "日志通过独立 ContentProvider 从 QQ 进程写回本 App，只接受 QQ 与模块自身 UID，"
                        + "不会混入其他 LSPosed 模块。",
                14,
                false
        ));

        LinearLayout firstActions = horizontalRow();
        firstActions.addView(actionButton("刷新", this::refreshLogs), weightedButtonParams());
        firstActions.addView(actionButton("复制", this::copyLogs), weightedButtonParams());
        root.addView(firstActions);

        LinearLayout secondActions = horizontalRow();
        secondActions.addView(actionButton("测试日志通道", this::testLogChannel), weightedButtonParams());
        secondActions.addView(actionButton("清空", this::clearLogs), weightedButtonParams());
        root.addView(secondActions);

        logStatus = text("", 13, false);
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
                        + "1. 先点“测试日志通道”，确认 App 能显示自检日志。\n"
                        + "2. 在 LSPosed 中确认作用域只勾选 QQ。\n"
                        + "3. 强制停止 QQ，再重新打开。\n"
                        + "4. 回到这里刷新，应看到“v3.0 安装完成”。\n"
                        + "5. 让另一个账号撤回一条普通文字消息，再刷新日志。\n\n"
                        + "设置变更需要强制停止并重启 QQ 后生效。",
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
        view.setPadding(0, dp(12), 0, dp(12));
        view.setChecked(preferences.getBoolean(key, defaultValue));
        view.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            Toast.makeText(this, "已保存，强制停止并重启 QQ 后生效", Toast.LENGTH_SHORT).show();
        });
        return view;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
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
        Bundle result = callLogProvider(ModulePrefs.LOG_METHOD_READ, null);
        if (result == null || !result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            String error = result == null
                    ? "日志 Provider 未返回结果"
                    : result.getString(ModulePrefs.LOG_RESULT_ERROR, "未知错误");
            logStatus.setText("读取失败：" + error);
            logView.setText("请确认安装的是 v3.0 APK。若刚覆盖安装，请先打开一次本 App。\n\n" + error);
            return;
        }

        String logs = result.getString(ModulePrefs.LOG_RESULT_TEXT, "").trim();
        if (logs.isEmpty()) {
            logStatus.setText("暂无模块专属日志");
            logView.setText("先点击“测试日志通道”。自检成功后，强制停止并重新打开 QQ。 ");
            return;
        }

        int lines = logs.split("\\R").length;
        logStatus.setText("已记录 " + lines + " 行，仅包含 QQAntiRevoke 日志");
        logView.setText(logs);
    }

    private void copyLogs() {
        Bundle result = callLogProvider(ModulePrefs.LOG_METHOD_READ, null);
        String logs = result == null ? "" : result.getString(ModulePrefs.LOG_RESULT_TEXT, "");
        if (logs.trim().isEmpty()) {
            Toast.makeText(this, "目前没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("QQAntiRevoke v3 日志", logs));
            Toast.makeText(this, "模块日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void testLogChannel() {
        Bundle extras = new Bundle();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        extras.putString(
                ModulePrefs.LOG_EXTRA_LINE,
                "[QQAntiRevoke] App 日志 Provider 自检成功，time=" + time
        );
        Bundle result = callLogProvider(ModulePrefs.LOG_METHOD_APPEND, extras);
        if (result != null && result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            Toast.makeText(this, "日志通道正常", Toast.LENGTH_SHORT).show();
        } else {
            String error = result == null
                    ? "Provider 未返回结果"
                    : result.getString(ModulePrefs.LOG_RESULT_ERROR, "未知错误");
            Toast.makeText(this, "日志通道失败：" + error, Toast.LENGTH_LONG).show();
        }
        refreshLogs();
    }

    private void clearLogs() {
        Bundle result = callLogProvider(ModulePrefs.LOG_METHOD_CLEAR, null);
        if (result != null && result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            Toast.makeText(this, "模块日志已清空", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show();
        }
        refreshLogs();
    }

    private Bundle callLogProvider(String method, Bundle extras) {
        try {
            return getContentResolver().call(ModulePrefs.LOG_URI, method, null, extras);
        } catch (Throwable throwable) {
            Bundle error = new Bundle();
            error.putBoolean(ModulePrefs.LOG_RESULT_OK, false);
            error.putString(ModulePrefs.LOG_RESULT_ERROR, throwable.toString());
            return error;
        }
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
