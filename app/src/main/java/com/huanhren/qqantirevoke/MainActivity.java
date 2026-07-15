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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String LOG_PREFIX = "[QQAntiRevoke]";
    private static final int MAX_ROOT_LOG_LINES = 1000;

    private SharedPreferences preferences;
    private EditText grayTemplateInput;
    private TextView logStatus;
    private TextView logView;
    private String lastDisplayedLogs = "";

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

        root.addView(text("QQ 防撤回 · NT v3.1", 25, true));
        root.addView(text(
                "目标环境：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n"
                        + "v3.1 在阻断 QQ NT 撤回推送后，会按你设置的模板插入一条本地灰色提示。",
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
                "显示本地撤回灰条",
                ModulePrefs.KEY_SHOW_GRAY_TIP,
                ModulePrefs.DEFAULT_SHOW_GRAY_TIP
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

        root.addView(text("\n撤回灰条内容", 21, true));
        root.addView(text(
                "默认显示“该用户尝试撤回一条消息”。你可以直接修改。\n"
                        + "可用变量：{operator}、{author}、{seq}、{peer}、{type}、"
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

        LinearLayout templateActions = horizontalRow();
        templateActions.addView(actionButton("保存灰条内容", this::saveGrayTipTemplate), weightedButtonParams());
        templateActions.addView(actionButton("恢复默认", this::resetGrayTipTemplate), weightedButtonParams());
        root.addView(templateActions);

        root.addView(text(
                "示例：\n"
                        + "{operator}尝试撤回一条消息\n"
                        + "{operator}撤回失败，消息已保留 [seq={seq}]\n"
                        + "有人想销毁证据，但没有成功",
                13,
                false
        ));

        root.addView(text("\n模块专属日志", 21, true));
        root.addView(text(
                "优先通过独立 ContentProvider 接收 QQ 进程日志。若 Provider 受 Android 15 宿主可见性限制，"
                        + "可点击“Root 读取 LSPosed 日志”，App 会执行只读 logcat 并仅保留本模块行。",
                14,
                false
        ));

        LinearLayout firstActions = horizontalRow();
        firstActions.addView(actionButton("刷新 Provider", this::refreshLogs), weightedButtonParams());
        firstActions.addView(actionButton("复制", this::copyLogs), weightedButtonParams());
        root.addView(firstActions);

        LinearLayout secondActions = horizontalRow();
        secondActions.addView(actionButton("测试 Provider", this::testLogChannel), weightedButtonParams());
        secondActions.addView(actionButton("清空 Provider", this::clearLogs), weightedButtonParams());
        root.addView(secondActions);

        Button rootLogButton = actionButton("Root 读取 LSPosed 日志（仅过滤本模块）", this::readRootFilteredLogs);
        root.addView(rootLogButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

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
                        + "1. 保存灰条内容并保持“显示本地撤回灰条”开启。\n"
                        + "2. 强制停止 QQ，再重新打开。\n"
                        + "3. 让另一个账号撤回一条普通消息。\n"
                        + "4. 原消息应保留，下面出现自定义灰色提示。\n"
                        + "5. 没有灰条时读取日志，查看“已插入”或“插入失败”。\n\n"
                        + "首次 Root 读取会弹出 Magisk 授权，请允许。设置变更需要强制停止并重启 QQ 后生效。",
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

    private void saveGrayTipTemplate() {
        String template = grayTemplateInput.getText() == null
                ? ""
                : grayTemplateInput.getText().toString().trim();
        if (template.isEmpty()) {
            template = ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE;
        }
        if (template.length() > ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH) {
            template = template.substring(0, ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH);
            Toast.makeText(this, "内容过长，已截断为 160 个字符", Toast.LENGTH_LONG).show();
        }
        preferences.edit()
                .putString(ModulePrefs.KEY_GRAY_TIP_TEMPLATE, template)
                .apply();
        grayTemplateInput.setText(template);
        grayTemplateInput.setSelection(template.length());
        Toast.makeText(this, "灰条内容已保存，重启 QQ 后生效", Toast.LENGTH_SHORT).show();
    }

    private void resetGrayTipTemplate() {
        preferences.edit()
                .putString(ModulePrefs.KEY_GRAY_TIP_TEMPLATE, ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE)
                .apply();
        grayTemplateInput.setText(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE);
        grayTemplateInput.setSelection(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE.length());
        Toast.makeText(this, "已恢复默认灰条内容，重启 QQ 后生效", Toast.LENGTH_SHORT).show();
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
            showLogs("读取 Provider 失败：" + error,
                    "请确认安装的是 v3.1 APK。Provider 不可用时点击 Root 读取。\n\n" + error);
            return;
        }

        String logs = result.getString(ModulePrefs.LOG_RESULT_TEXT, "").trim();
        if (logs.isEmpty()) {
            showLogs("Provider 暂无日志",
                    "先点击“测试 Provider”。自检成功后，强制停止并重新打开 QQ。若仍无 QQ 日志，请使用 Root 读取。");
            return;
        }

        int lines = logs.split("\\R").length;
        showLogs("Provider 已记录 " + lines + " 行，仅包含 QQAntiRevoke 日志", logs);
    }

    private void copyLogs() {
        if (lastDisplayedLogs.trim().isEmpty()) {
            Toast.makeText(this, "目前没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("QQAntiRevoke v3.1 日志", lastDisplayedLogs));
            Toast.makeText(this, "当前显示的日志已复制", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Provider 日志通道正常", Toast.LENGTH_SHORT).show();
        } else {
            String error = result == null
                    ? "Provider 未返回结果"
                    : result.getString(ModulePrefs.LOG_RESULT_ERROR, "未知错误");
            Toast.makeText(this, "Provider 日志通道失败：" + error, Toast.LENGTH_LONG).show();
        }
        refreshLogs();
    }

    private void clearLogs() {
        Bundle result = callLogProvider(ModulePrefs.LOG_METHOD_CLEAR, null);
        if (result != null && result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            Toast.makeText(this, "Provider 日志已清空", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show();
        }
        refreshLogs();
    }

    private void readRootFilteredLogs() {
        logStatus.setText("正在请求 Root 并读取 logcat…");
        new Thread(() -> {
            ArrayDeque<String> matched = new ArrayDeque<>();
            String error = null;
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "logcat -d -v threadtime")
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                ))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.contains(LOG_PREFIX)) {
                            continue;
                        }
                        if (matched.size() >= MAX_ROOT_LOG_LINES) {
                            matched.removeFirst();
                        }
                        matched.addLast(line);
                    }
                }
                if (!process.waitFor(12, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    error = "Root logcat 读取超时";
                } else if (process.exitValue() != 0) {
                    error = "su/logcat 返回代码 " + process.exitValue()
                            + "。请在 Magisk 中允许本 App 的 Root 权限。";
                }
            } catch (Throwable throwable) {
                error = throwable.toString();
                if (process != null) {
                    process.destroy();
                }
            }

            String finalError = error;
            String logs = String.join("\n", matched);
            runOnUiThread(() -> {
                if (finalError != null) {
                    showLogs("Root 日志读取失败", finalError);
                } else if (logs.isEmpty()) {
                    showLogs("Root 日志中没有 QQAntiRevoke 记录",
                            "请先强制停止并重新打开 QQ，再测试一次撤回。确认 LSPosed 模块和 QQ 作用域已启用。");
                } else {
                    showLogs("Root 已过滤出 " + matched.size() + " 行 QQAntiRevoke 日志", logs);
                }
            });
        }, "QQAntiRevoke-RootLogReader").start();
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

    private void showLogs(String status, String logs) {
        lastDisplayedLogs = logs == null ? "" : logs;
        logStatus.setText(status);
        logView.setText(lastDisplayedLogs);
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
