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

/** Small reliable launcher and log reader for v3.2.1. */
public final class DashboardActivity extends Activity {
    private static final int MAX_OUTPUT = 250_000;

    private TextView status;
    private TextView logs;
    private String currentLogs = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("QQ 防撤回 NT v3.2.1", 25, true);
        root.addView(title);
        root.addView(text(
                "针对 QQ 9.2.10：防撤回、可编辑灰条、语音转发、QQ 设置入口和独立日志。",
                15,
                false
        ));

        Button settingsButton = button("打开完整模块设置", () ->
                startActivity(new Intent(this, MainActivity.class)));
        root.addView(settingsButton);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Root 搜索日志", this::readLogs), weighted());
        row.addView(button("复制", this::copyLogs), weighted());
        root.addView(row);

        status = text("尚未读取日志", 14, false);
        root.addView(status);

        logs = text("", 12, false);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextIsSelectable(true);
        logs.setMinHeight(dp(420));
        logs.setPadding(dp(10), dp(10), dp(10), dp(10));
        logs.setBackgroundResource(android.R.drawable.edit_text);
        root.addView(logs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text(
                "读取范围：/data/user/*、/data/user_de/*、/data/data 和 QQ 外部 files 目录。"
                        + " 这可以兼容小米应用双开或其他 Android 用户 ID。",
                13,
                false
        ));

        setContentView(scroll);
    }

    private void readLogs() {
        status.setText("正在请求 Root 并搜索 QQ 日志…");
        String command = "FOUND=0; "
                + "for BASE in /data/user/*/com.tencent.mobileqq/files/qqantirevoke "
                + "/data/user_de/*/com.tencent.mobileqq/files/qqantirevoke "
                + "/data/data/com.tencent.mobileqq/files/qqantirevoke "
                + "/sdcard/Android/data/com.tencent.mobileqq/files/qqantirevoke; do "
                + "[ -d \"$BASE\" ] || continue; echo \"===== $BASE =====\"; FOUND=1; "
                + "ls -la \"$BASE\" 2>&1; "
                + "for F in main.log main.log.old msf.log msf.log.old; do "
                + "[ -f \"$BASE/$F\" ] && { echo \"----- $F -----\"; tail -n 1500 \"$BASE/$F\"; }; "
                + "done; done; "
                + "if [ \"$FOUND\" = 0 ]; then echo __QQANTIREVOKE_NO_LOG_DIRECTORY__; fi";

        new Thread(() -> {
            StringBuilder output = new StringBuilder();
            String error = null;
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() < MAX_OUTPUT) {
                            output.append(line).append('\n');
                        }
                    }
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    error = "Root 搜索超时";
                } else if (process.exitValue() != 0) {
                    error = "su 返回代码 " + process.exitValue() + "\n" + output;
                }
            } catch (Throwable throwable) {
                error = throwable.toString();
                if (process != null) {
                    process.destroy();
                }
            }
            String finalError = error;
            String result = output.toString();
            runOnUiThread(() -> {
                if (finalError != null) {
                    show("Root 日志读取失败", finalError);
                } else if (result.contains("__QQANTIREVOKE_NO_LOG_DIRECTORY__")) {
                    show("未找到 QQAntiRevoke 日志目录",
                            "模块仍可防撤回，但日志文件没有创建。请把 LSPosed 中从“v3.2.1 同步日志探针”开始的内容发来。\n\n"
                                    + result);
                } else {
                    show("已读取 QQ 独立日志", result);
                }
            });
        }, "QQAntiRevoke-V321-LogReader").start();
    }

    private void copyLogs() {
        if (currentLogs.trim().isEmpty()) {
            Toast.makeText(this, "没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("QQAntiRevoke v3.2.1", currentLogs));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void show(String state, String value) {
        currentLogs = value == null ? "" : value;
        status.setText(state);
        logs.setText(currentLogs);
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
