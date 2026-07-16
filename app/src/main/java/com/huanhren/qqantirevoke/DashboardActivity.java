package com.huanhren.qqantirevoke;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Reliable front page for reading QQ-host logs on rooted Android 15 devices. */
public final class DashboardActivity extends Activity {
    private static final int MAX_OUTPUT_CHARS = 350_000;

    private TextView statusView;
    private TextView logView;
    private String displayedLogs = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text("QQ 防撤回 NT v3.3.1", 25, true));
        root.addView(text(
                "语音转发和防撤回已经命中。此页面专门解决 Android 15 下 QQ 日志广播无法送达的问题。",
                15,
                false
        ));

        Button settings = button("打开完整模块设置", () ->
                startActivity(new Intent(this, MainActivity.class)));
        root.addView(settings);

        LinearLayout row1 = row();
        row1.addView(button("Root 读取 QQ 日志", this::readHostLogsWithRoot), weighted());
        row1.addView(button("读取 App 日志", this::readAppLogs), weighted());
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(button("复制当前日志", this::copyLogs), weighted());
        row2.addView(button("清空 App 日志", this::clearAppLogs), weighted());
        root.addView(row2);

        statusView = text("建议先点击“Root 读取 QQ 日志”", 14, false);
        statusView.setPadding(0, dp(12), 0, dp(8));
        root.addView(statusView);

        logView = text("", 12, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMinHeight(dp(520));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundResource(android.R.drawable.edit_text);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text(
                "\nRoot 读取范围：\n"
                        + "/data/user/*/com.tencent.mobileqq/files/qqantirevoke\n"
                        + "/data/user_de/*/com.tencent.mobileqq/files/qqantirevoke\n"
                        + "/data/data/com.tencent.mobileqq/files/qqantirevoke\n\n"
                        + "只读取本模块创建的 main.log、msf.log 及轮换文件，不会混入其他 LSPosed 模块。",
                13,
                false
        ));

        setContentView(scroll);
    }

    private void readHostLogsWithRoot() {
        statusView.setText("正在请求 Root 并搜索 QQ 宿主日志……");
        String command = "FOUND=0; "
                + "for BASE in /data/user/*/com.tencent.mobileqq/files/qqantirevoke "
                + "/data/user_de/*/com.tencent.mobileqq/files/qqantirevoke "
                + "/data/data/com.tencent.mobileqq/files/qqantirevoke; do "
                + "[ -d \"$BASE\" ] || continue; FOUND=1; "
                + "echo \"===== $BASE =====\"; ls -la \"$BASE\" 2>&1; "
                + "for F in main.log.old main.log msf.log.old msf.log; do "
                + "if [ -f \"$BASE/$F\" ]; then "
                + "echo \"----- $F -----\"; tail -n 1800 \"$BASE/$F\"; fi; done; done; "
                + "if [ \"$FOUND\" = 0 ]; then echo __QQANTIREVOKE_NO_HOST_LOG__; fi";

        runRoot(command, result -> {
            if (result.error != null) {
                showLogs("Root 读取失败", result.error);
                return;
            }
            String output = result.output.trim();
            if (output.contains("__QQANTIREVOKE_NO_HOST_LOG__")) {
                showLogs(
                        "未找到 QQ 私有日志目录",
                        "Root 命令执行成功，但没有找到 qqantirevoke 目录。\n"
                                + "请先强制停止并重新打开 QQ，再操作一次语音转发或撤回，然后重试。\n\n"
                                + output
                );
                return;
            }
            showLogs("已读取 QQ 宿主独立日志", output);
        });
    }

    private void readAppLogs() {
        try {
            Bundle result = getContentResolver().call(
                    ModulePrefs.LOG_URI,
                    ModulePrefs.LOG_METHOD_READ,
                    null,
                    null
            );
            if (result == null || !result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
                String error = result == null
                        ? "Provider 未返回结果"
                        : result.getString(ModulePrefs.LOG_RESULT_ERROR, "未知错误");
                showLogs("读取 App 日志失败", error);
                return;
            }
            String value = result.getString(ModulePrefs.LOG_RESULT_TEXT, "").trim();
            showLogs(
                    value.isEmpty() ? "App 日志为空" : "已读取 App 日志",
                    value.isEmpty() ? "广播通道没有收到 QQ 日志，请使用 Root 读取。" : value
            );
        } catch (Throwable throwable) {
            showLogs("读取 App 日志失败", throwable.toString());
        }
    }

    private void clearAppLogs() {
        try {
            Bundle result = getContentResolver().call(
                    ModulePrefs.LOG_URI,
                    ModulePrefs.LOG_METHOD_CLEAR,
                    null,
                    null
            );
            if (result != null && result.getBoolean(ModulePrefs.LOG_RESULT_OK, false)) {
                showLogs("App 日志已清空", "");
            } else {
                Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable throwable) {
            Toast.makeText(this, "清空失败：" + throwable, Toast.LENGTH_LONG).show();
        }
    }

    private void copyLogs() {
        if (displayedLogs.trim().isEmpty()) {
            Toast.makeText(this, "没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "QQAntiRevoke v3.3.1 logs",
                    displayedLogs
            ));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void runRoot(String command, RootCallback callback) {
        new Thread(() -> {
            StringBuilder output = new StringBuilder();
            String error = null;
            Process process = null;
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
                        if (output.length() < MAX_OUTPUT_CHARS) {
                            output.append(line).append('\n');
                        }
                    }
                }
                if (!process.waitFor(25, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    error = "Root 命令超时";
                } else if (process.exitValue() != 0) {
                    error = "su 返回代码 " + process.exitValue() + "\n" + output;
                }
            } catch (Throwable throwable) {
                error = throwable.toString();
                if (process != null) {
                    process.destroy();
                }
            }
            RootResult result = new RootResult(output.toString(), error);
            runOnUiThread(() -> callback.onResult(result));
        }, "QQAntiRevoke-RootHostLogReader").start();
    }

    private void showLogs(String status, String value) {
        displayedLogs = value == null ? "" : value;
        statusView.setText(status);
        logView.setText(displayedLogs);
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

    private interface RootCallback {
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
