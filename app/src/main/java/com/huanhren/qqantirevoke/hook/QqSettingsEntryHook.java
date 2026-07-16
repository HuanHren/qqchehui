package com.huanhren.qqantirevoke.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds a small entry to QQ 9.2.10's own Settings page. */
final class QqSettingsEntryHook {
    private static final String MAIN_SETTING_FRAGMENT =
            "com.tencent.mobileqq.setting.main.MainSettingFragment";
    private static final String ENTRY_TAG = "qqantirevoke.settings.entry.v3.2";

    private final Context applicationContext;
    private final ClassLoader classLoader;
    private final AtomicBoolean loggedFailure = new AtomicBoolean(false);

    QqSettingsEntryHook(Context context, ClassLoader classLoader) {
        this.applicationContext = context.getApplicationContext();
        this.classLoader = classLoader;
    }

    int install() {
        Class<?> fragment = XposedHelpers.findClassIfExists(MAIN_SETTING_FRAGMENT, classLoader);
        if (fragment == null) {
            HookLog.info("QQ 设置入口未安装：未找到 MainSettingFragment");
            return 0;
        }
        int count = 0;
        try {
            XposedHelpers.findAndHookMethod(fragment, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    postInject(param.thisObject, null);
                }
            });
            count++;
        } catch (Throwable throwable) {
            HookLog.debug("MainSettingFragment.onResume Hook 不可用：" + throwable);
        }

        for (Method method : fragment.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!View.class.isAssignableFrom(method.getReturnType())
                    || parameters.length != 3
                    || parameters[0] != LayoutInflater.class
                    || !ViewGroup.class.isAssignableFrom(parameters[1])
                    || parameters[2] != Bundle.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        postInject(param.thisObject, result instanceof View ? (View) result : null);
                    }
                });
                count++;
            } catch (Throwable throwable) {
                HookLog.error("Hook QQ 设置页面创建方法失败：" + method, throwable);
            }
        }
        HookLog.info("QQ 设置入口 Hook 安装数量=" + count);
        return count;
    }

    private void postInject(Object fragment, View knownRoot) {
        View root = knownRoot;
        if (root == null) {
            try {
                Object value = XposedHelpers.callMethod(fragment, "getView");
                if (value instanceof View) {
                    root = (View) value;
                }
            } catch (Throwable ignored) {
                // Fragment view may not be attached yet.
            }
        }
        if (root == null) {
            return;
        }
        View finalRoot = root;
        root.postDelayed(() -> inject(finalRoot), 260L);
    }

    private void inject(View root) {
        try {
            if (findByTag(root) != null) {
                return;
            }
            TextView aboutText = findText(root, "关于QQ与帮助");
            if (aboutText == null) {
                aboutText = findText(root, "通用");
            }
            ViewGroup parent;
            int index;
            View anchor;
            if (aboutText != null) {
                anchor = findRowAncestor(aboutText);
                parent = anchor == null || !(anchor.getParent() instanceof ViewGroup)
                        ? null
                        : (ViewGroup) anchor.getParent();
                index = parent == null ? -1 : parent.indexOfChild(anchor);
            } else {
                parent = findLargestContainer(root, null);
                index = parent == null ? -1 : parent.getChildCount();
            }
            if (parent == null || index < 0) {
                if (loggedFailure.compareAndSet(false, true)) {
                    HookLog.info("QQ 设置入口注入失败：未找到合适的设置列表容器");
                }
                return;
            }

            View entry = createEntry(parent.getContext());
            ViewGroup.LayoutParams params = anchorLayoutParams(aboutText, entry);
            parent.addView(entry, Math.min(index, parent.getChildCount()), params);
            HookLog.info("已在 QQ 设置页面插入“QQ 防撤回 NT”入口");
        } catch (Throwable throwable) {
            HookLog.error("注入 QQ 设置入口失败", throwable);
        }
    }

    private View createEntry(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setTag(ENTRY_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 22), dp(context, 15), dp(context, 18), dp(context, 15));
        row.setMinimumHeight(dp(context, 64));
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveColor(context, android.R.attr.colorBackground, Color.WHITE));
        background.setCornerRadius(dp(context, 14));
        row.setBackground(background);

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("QQ 防撤回 NT");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        title.setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.BLACK));
        TextView subtitle = new TextView(context);
        subtitle.setText("防撤回、灰条与语音转发");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(resolveColor(context, android.R.attr.textColorSecondary, 0xff888888));
        textColumn.addView(title);
        textColumn.addView(subtitle);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = new TextView(context);
        version.setText("v3.2  ›");
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        version.setTextColor(resolveColor(context, android.R.attr.textColorSecondary, 0xff888888));
        row.addView(version);

        row.setOnClickListener(view -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        ModulePrefs.MODULE_PACKAGE,
                        ModulePrefs.MODULE_PACKAGE + ".MainActivity"
                ));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable throwable) {
                HookLog.error("从 QQ 设置打开模块 App 失败", throwable);
            }
        });
        return row;
    }

    private ViewGroup.LayoutParams anchorLayoutParams(TextView anchorText, View entry) {
        if (anchorText != null) {
            View anchor = findRowAncestor(anchorText);
            if (anchor != null && anchor.getLayoutParams() != null) {
                ViewGroup.LayoutParams old = anchor.getLayoutParams();
                if (old instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams source = (ViewGroup.MarginLayoutParams) old;
                    ViewGroup.MarginLayoutParams copy = new ViewGroup.MarginLayoutParams(
                            source.width,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    copy.leftMargin = source.leftMargin;
                    copy.rightMargin = source.rightMargin;
                    copy.topMargin = source.topMargin;
                    copy.bottomMargin = source.bottomMargin;
                    return copy;
                }
                return new ViewGroup.LayoutParams(old.width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(entry.getContext(), 14), dp(entry.getContext(), 8),
                dp(entry.getContext(), 14), dp(entry.getContext(), 8));
        return params;
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

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().contains(expected)) {
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

    private static View findRowAncestor(View view) {
        View current = view;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) current.getParent();
                if (parent.getChildCount() >= 2 && current != view) {
                    return current;
                }
                current = parent;
            } else {
                break;
            }
        }
        return view;
    }

    private static ViewGroup findLargestContainer(View view, ViewGroup best) {
        if (!(view instanceof ViewGroup)) {
            return best;
        }
        ViewGroup group = (ViewGroup) view;
        if (best == null || group.getChildCount() > best.getChildCount()) {
            best = group;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            best = findLargestContainer(group.getChildAt(i), best);
        }
        return best;
    }

    private static int resolveColor(Context context, int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getColor(value.resourceId);
                } catch (Throwable ignored) {
                    // Use data/fallback.
                }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return fallback;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
