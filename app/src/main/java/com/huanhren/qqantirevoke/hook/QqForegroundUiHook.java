package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Handles UI work only after QQ has a real foreground Activity. */
final class QqForegroundUiHook {
    private static final String ENTRY_TAG = "qqantirevoke.settings.overlay.v3.2.1";

    private final boolean settingsEntryEnabled;
    private final boolean startupToastEnabled;
    private final AtomicBoolean toastShown = new AtomicBoolean(false);
    private final AtomicBoolean settingsSuccessLogged = new AtomicBoolean(false);

    QqForegroundUiHook(boolean settingsEntryEnabled, boolean startupToastEnabled) {
        this.settingsEntryEnabled = settingsEntryEnabled;
        this.startupToastEnabled = startupToastEnabled;
    }

    int install() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (activity == null) {
                        return;
                    }
                    if (startupToastEnabled && toastShown.compareAndSet(false, true)) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                Toast.makeText(activity, "QQ 防撤回 NT v3.2.1 已加载",
                                        Toast.LENGTH_SHORT).show();
                                HookLog.info("已在 QQ 前台 Activity 显示模块加载提示");
                            } catch (Throwable throwable) {
                                HookLog.error("前台显示模块加载提示失败", throwable);
                            }
                        }, 350L);
                    }
                    if (settingsEntryEnabled) {
                        Window window = activity.getWindow();
                        View root = window == null ? null : window.getDecorView();
                        if (root != null) {
                            root.postDelayed(() -> inspectAndInject(activity, root), 500L);
                        }
                    }
                }
            });
            HookLog.info("已安装 QQ 前台 UI Hook：设置入口=" + settingsEntryEnabled
                    + "，加载提示=" + startupToastEnabled);
            return 1;
        } catch (Throwable throwable) {
            HookLog.error("安装 QQ 前台 UI Hook 失败", throwable);
            return 0;
        }
    }

    private void inspectAndInject(Activity activity, View root) {
        try {
            if (findByTag(root) != null) {
                return;
            }
            TextView account = findText(root, "账号与安全");
            TextView about = findText(root, "关于QQ与帮助");
            TextView general = findText(root, "通用");
            if (account == null || (about == null && general == null)) {
                return;
            }

            TextView anchorText = about != null ? about : general;
            View anchorRow = findRowAncestor(anchorText);
            if (anchorRow != null && anchorRow.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) anchorRow.getParent();
                if (!parent.getClass().getName().contains("RecyclerView")) {
                    View entry = createListEntry(activity);
                    int index = Math.max(0, parent.indexOfChild(anchorRow));
                    ViewGroup.LayoutParams params = copyLayoutParams(anchorRow);
                    parent.addView(entry, Math.min(index, parent.getChildCount()), params);
                    if (settingsSuccessLogged.compareAndSet(false, true)) {
                        HookLog.info("已通过当前设置页 View 树插入模块设置入口，activity="
                                + activity.getClass().getName());
                    }
                    return;
                }
            }

            if (root instanceof ViewGroup) {
                addOverlayButton(activity, (ViewGroup) root);
            }
        } catch (Throwable throwable) {
            HookLog.error("识别并注入 QQ 设置入口失败", throwable);
        }
    }

    private View createListEntry(Activity activity) {
        TextView entry = createButton(activity, "QQ 防撤回 NT   v3.2.1  ›");
        entry.setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 20), dp(activity, 16));
        entry.setMinHeight(dp(activity, 64));
        return entry;
    }

    private void addOverlayButton(Activity activity, ViewGroup root) {
        TextView entry = createButton(activity, "QQ 防撤回 NT");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM
        );
        params.setMargins(dp(activity, 16), dp(activity, 16), dp(activity, 18), dp(activity, 88));
        try {
            root.addView(entry, params);
        } catch (Throwable first) {
            ViewGroup.LayoutParams fallback = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            root.addView(entry, fallback);
        }
        if (settingsSuccessLogged.compareAndSet(false, true)) {
            HookLog.info("QQ 设置列表无法直接插入，已显示右下角模块设置按钮，activity="
                    + activity.getClass().getName());
        }
    }

    private TextView createButton(Activity activity, String text) {
        TextView entry = new TextView(activity);
        entry.setTag(ENTRY_TAG);
        entry.setText(text);
        entry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        entry.setTextColor(Color.BLACK);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12));
        entry.setClickable(true);
        entry.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff2f2f2);
        background.setCornerRadius(dp(activity, 18));
        entry.setBackground(background);
        entry.setElevation(dp(activity, 5));
        entry.setOnClickListener(view -> openModule(activity));
        return entry;
    }

    private void openModule(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    ModulePrefs.MODULE_PACKAGE,
                    ModulePrefs.MODULE_PACKAGE + ".DashboardActivity"
            ));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable throwable) {
            HookLog.error("从 QQ 设置打开模块 App 失败", throwable);
            Toast.makeText(activity, "无法打开模块设置", Toast.LENGTH_SHORT).show();
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(View anchor) {
        ViewGroup.LayoutParams source = anchor.getLayoutParams();
        if (source instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams old = (ViewGroup.MarginLayoutParams) source;
            ViewGroup.MarginLayoutParams copy = new ViewGroup.MarginLayoutParams(
                    old.width,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            copy.leftMargin = old.leftMargin;
            copy.topMargin = old.topMargin;
            copy.rightMargin = old.rightMargin;
            copy.bottomMargin = old.bottomMargin;
            return copy;
        }
        return new ViewGroup.LayoutParams(
                source == null ? ViewGroup.LayoutParams.MATCH_PARENT : source.width,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static View findRowAncestor(View view) {
        View current = view;
        for (int depth = 0; depth < 7 && current != null; depth++) {
            if (!(current.getParent() instanceof ViewGroup)) {
                break;
            }
            ViewGroup parent = (ViewGroup) current.getParent();
            if (parent.getParent() instanceof ViewGroup
                    && ((ViewGroup) parent.getParent()).getChildCount() >= 2) {
                return parent;
            }
            current = parent;
        }
        return null;
    }

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && value.toString().contains(expected)) {
                return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findText(group.getChildAt(i), expected);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static View findByTag(View view) {
        if (ENTRY_TAG.equals(view.getTag())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findByTag(group.getChildAt(i));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
