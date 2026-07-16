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
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int MAX_ROOT_LOG_CHARS = 250_000;

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

        root.addView(text("QQ 防撤回 · NT v3.2", 25, true));
        root.addView(text(
                "目标环境：Android 15、QQ 9.2.10、LSPosed Zygisk。\n\n"
                        + "v3.2 包含 NT 防撤回、可编辑灰条、QQ 设置入口、加载提示和语音转发。",
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
                "QQ 启动时提示模块加载成功",
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
        LinearLayout templateActions = horizontalRow();
        templateActions.addView(actionButton("保存内容", this::saveGrayTipTemplate), weightedButtonParams());
        templateActions.addView(actionButton("恢复默认", this::resetGrayTipTemplate), weightedButtonParams());
        root.addView(templateActions);

        root.addView(section("模块专属日志"));
        root.addView(text(
                "v3.2 会由 QQ 进程直接写入自己的私有日志文件。"
                        + "“Root 读取”不再依赖 Android logcat，因此不会被其他 LSPosed 模块淹没。",
                14,
                false
        ));

        LinearLayout rootActions = horizontalRow();
        rootActions.addView(actionButton("Root 读取", this::readRootFileLogs), weightedButtonParams());
        rootActions.addView(actionButton("复制", this::copyLogs), weightedButtonParams());
        root.addView(rootActions);

        LinearLayout clearActions = horizontalRow();
        clearActions.addView(actionButton("清空 Root 日志", this::clearRootFileLogs), weightedButtonParams());
        clearActions.addView(actionButton("读取 Provider", this::refreshProviderLogs), weightedButtonParams());
        root.addView(clearActions);

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
                "\n使用方法：\n"
                        + "1. 覆盖安装后，在 LSPosed 中保持 QQ 作用域启用。\n"
                        + "2. 强制停止并重新打开 QQ，应看到“v3.2 已加载”提示。\n"
                        + "3. QQ 设置页会出现“QQ 防撤回 NT”入口。\n"
                        + "4. 长按别人发来的语音，点击 QQ 原来的“转发”，选择联系人后发送。\n"
                        + "5. 出现问题时回到这里点击 Root 读取。",
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

    private void saveGrayTipTemplate() {
        String template = grayTemplateInput.getText() == null
                ? ""
                : grayTemplateInput.getText().toString().trim();
        if (template.isEmpty()) {
            template = ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE;
        }
        if (template.length() > ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH) {
            template = template.substring(0, ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH);
            Toast.makeText(this, "内容过长，已截断", Toast.LENGTH_LONG).show();
        }
        preferences.edit().putString(ModulePrefs.KEY_GRAY_TIP_TEMPLATE, template).apply();
        grayTemplateInput.setText(template);
        grayTemplateInput.setSelection(template.length());
        Toast.makeText(this, "灰条内容已保存", Toast.LENGTH_SHORT).show();
    }

    private void resetGrayTipTemplate() {
        preferences.edit()
                .putString(ModulePrefs.KEY_GRAY_TIP_TEMPLATE, ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE)
                .apply();
        grayTemplateInput.setText(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE);
        grayTemplateInput.setSelection(ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE.length());
        Toast.makeText(this, "已恢复默认内容", Toast.LENGTH_SHORT).show();
    }

    private void readRootFileLogs() {
        logStatus.setText("正在请求 Root 并读取 QQ 私有日志…");
        runRootCommand(buildReadLogsCommand(), "QQAntiRevoke-ReadHostLogs", result -> {
            if (result.error != null) {
                showLogs("Root 日志读取失败", result.error);
            } else if (result.output.trim().isEmpty()) {
                showLogs(
                        "QQ 私有日志为空",
                        "请先强制停止并重新打开 QQ。确认 LSPosed 作用域已勾选 QQ，并允许本 App 的 Root 权限。"
                );
            } else {
                showLogs("已读取 QQ 私有日志", result.output);
            }
        });
    }

    private void clearRootFileLogs() {
        logStatus.setText("正在清空 QQ 私有日志…");
        String command = "BASE=/data/user/0/com.tencent.mobileqq/files/qqantirevoke; "
                + "[ -d \"$BASE\" ] || BASE=/data/data/com.tencent.mobileqq/files/qqantirevoke; "
                + "rm -f \"$BASE\"/main.log \"$BASE\"/main.log.old "
                + "\"$BASE\"/msf.log \"$BASE\"/msf.log.old; echo cleared";
        runRootCommand(command, "QQAntiRevoke-ClearHostLogs", result -> {
            if (result.error != null) {
                showLogs("清空失败", result.error);
            } else {
                showLogs("QQ 私有日志已清空", "");
            }
        });
    }

    private String buildReadLogsCommand() {
        return "BASE=/data/user/0/com.tencent.mobileqq/files/qqantirevoke; "
                + "[ -d \"$BASE\" ] || BASE=/data/data/com.tencent.mobileqq/files/qqantirevoke; "
                + "if [ ! -d \"$BASE\" ]; then exit 0; fi; "
                + "for F in main.log main.log.old msf.log msf.log.old; do "
                + "if [ -f \"$BASE/$F\" ]; then echo \"===== $F =====\"; tail -n 1200 \"$BASE/$F\"; fi; "
                + "done";
    }

    private void refreshProviderLogs() {
        Bundle result = callLogProvider(ModulePrefs.LOG_METHOD_READ, null);
        if (result == null || !result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
            String error = result == null
                    ? "Provider 未返回结果"
                    : result.getString(ModulePrefs.LOG_RESULT_ERROR, "未知错误");
            showLogs("读取 Provider 失败", error);
            return;
        }
        String logs = result.getString(ModulePrefs.LOG_RESULT_TEXT, "").trim();
        showLogs(logs.isEmpty() ? "Provider 暂无日志" : "已读取 Provider 日志", logs);
    }

    private void copyLogs() {
        if (lastDisplayedLogs.trim().isEmpty()) {
            Toast.makeText(this, "目前没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("QQAntiRevoke v3.2 日志", lastDisplayedLogs));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void runRootCommand(String command, String threadName, RootResultCallback callback) {
        new Thread(() -> {
            Process process = null;
            String error = null;
            StringBuilder output = new StringBuilder();
            try {
                process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                ))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() < MAX_ROOT_LOG_CHARS) {
                            output.append(line).append('\n');
                        }
                    }
                }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    error = "Root 命令超时";
                } else if (process.exitValue() != 0) {
                    error = "su 返回代码 " + process.exitValue()
                            + "。请在 Magisk 中允许本 App 的 Root 权限。\n\n" + output;
                }
            } catch (Throwable throwable) {
                error = throwable.toString();
                if (process != null) {
                    process.destroy();
                }
            }
            RootResult result = new RootResult(output.toString(), error);
            runOnUiThread(() -> callback.onResult(result));
        }, threadName).start();
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

    private TextView section(String value) {
        TextView view = text("\n" + value, 21, true);
        view.setPadding(0, dp(8), 0, dp(8));
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

    private interface RootResultCallback {
        void onResult(RootResult result);
    }

    private static final class RootResult {
        final String output;
        final String error;

        RootResult(String output, String error) {
            this.output = output;
            this.error = error;
        }
    }
}
