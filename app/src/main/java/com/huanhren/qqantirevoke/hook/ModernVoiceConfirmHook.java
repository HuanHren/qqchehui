package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Replaces QQ's legacy-themed voice-forward confirmation with a modern rounded dialog. */
final class ModernVoiceConfirmHook {
    private static final String FORWARD_BASE_OPTION =
            "com.tencent.mobileqq.forward.ForwardBaseOption";
    private static final String DIRECT_FORWARD_ACTIVITY =
            "com.tencent.mobileqq.activity.DirectForwardActivity";
    private static final String EXTRA_PATH = "ptt_forward_path";
    private static final String EXTRA_DURATION = "qqantirevoke_ptt_duration";

    private final ClassLoader classLoader;
    private final PttVoiceForwardHook delegate;

    private Method findBundle;
    private Method parseForwardTargets;
    private Method findActivity;
    private Method currentActivity;
    private Method describeTargets;
    private Method sendVoice;

    ModernVoiceConfirmHook(Context context, ClassLoader classLoader,
            PreferenceReader preferences) {
        this.classLoader = classLoader;
        this.delegate = new PttVoiceForwardHook(context, classLoader, preferences);
    }

    int install() {
        try {
            prepareDelegateMethods();
            Class<?> baseOption = findForwardBaseOption();
            if (baseOption == null) {
                return 0;
            }
            Method confirm = findConfirmationMethod(baseOption);
            if (confirm == null) {
                return 0;
            }
            confirm.setAccessible(true);
            XposedBridge.hookMethod(confirm, new XC_MethodHook(100) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    handleConfirmation(param);
                }
            });
            return 1;
        } catch (Throwable throwable) {
            HookLog.error("安装现代语音转发确认界面失败", throwable);
            return 0;
        }
    }

    private void prepareDelegateMethods() throws ReflectiveOperationException {
        Class<?> hookClass = PttVoiceForwardHook.class;
        Class<?> targetClass = Class.forName(
                hookClass.getName() + "$ForwardTarget",
                false,
                hookClass.getClassLoader()
        );
        findBundle = hookClass.getDeclaredMethod("findBundle", Object.class);
        parseForwardTargets = hookClass.getDeclaredMethod("parseForwardTargets", Bundle.class);
        findActivity = hookClass.getDeclaredMethod("findActivity", Object.class);
        currentActivity = hookClass.getDeclaredMethod("currentActivity");
        describeTargets = hookClass.getDeclaredMethod("describeTargets", List.class);
        sendVoice = hookClass.getDeclaredMethod("sendVoice", File.class, int.class, targetClass);
        for (Method method : new Method[]{
                findBundle, parseForwardTargets, findActivity,
                currentActivity, describeTargets, sendVoice
        }) {
            method.setAccessible(true);
        }
    }

    private Class<?> findForwardBaseOption() {
        Class<?> base = XposedHelpers.findClassIfExists(FORWARD_BASE_OPTION, classLoader);
        if (base != null) {
            return base;
        }
        Class<?> direct = XposedHelpers.findClassIfExists(DIRECT_FORWARD_ACTIVITY, classLoader);
        if (direct == null) {
            return null;
        }
        for (Field field : direct.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (!Modifier.isStatic(field.getModifiers())
                    && Modifier.isAbstract(type.getModifiers())
                    && !type.getName().startsWith("android.")) {
                return type;
            }
        }
        return null;
    }

    private static Method findConfirmationMethod(Class<?> baseOption) {
        try {
            return baseOption.getDeclaredMethod("buildConfirmDialog");
        } catch (NoSuchMethodException ignored) {
            for (Method method : baseOption.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType() == void.class
                        && Modifier.isFinal(method.getModifiers())) {
                    return method;
                }
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleConfirmation(XC_MethodHook.MethodHookParam param) {
        try {
            Bundle data = (Bundle) findBundle.invoke(delegate, param.thisObject);
            if (data == null || !data.containsKey(EXTRA_PATH)) {
                return;
            }
            // Priority 100 runs before the old priority-51 AlertDialog callback.
            param.setResult(null);

            String path = data.getString(EXTRA_PATH);
            int duration = data.getInt(EXTRA_DURATION, 0);
            File file = path == null ? null : new File(path);
            Activity activity = (Activity) findActivity.invoke(delegate, param.thisObject);
            if (activity == null) {
                activity = (Activity) currentActivity.invoke(delegate);
            }
            if (activity == null || file == null || !file.isFile()) {
                showToast(activity, "语音文件不存在，请先播放一次后重试");
                return;
            }

            List<Object> targets = (List<Object>) parseForwardTargets.invoke(delegate, data);
            if (targets == null || targets.isEmpty()) {
                showToast(activity, "没有解析到转发目标");
                return;
            }
            String targetLabel = String.valueOf(describeTargets.invoke(null, targets));
            showModernDialog(activity, file, duration, targets, targetLabel);
        } catch (Throwable throwable) {
            HookLog.error("显示现代语音转发确认界面失败", unwrap(throwable));
        }
    }

    private void showModernDialog(Activity activity, File file, int duration,
            List<Object> targets, String targetLabel) {
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int surface = dark ? 0xff242529 : Color.WHITE;
        int primaryText = dark ? 0xfff4f5f7 : 0xff17181a;
        int secondaryText = dark ? 0xffa9adb4 : 0xff777b82;
        int secondarySurface = dark ? 0xff34363b : 0xfff1f2f4;
        int qqBlue = 0xff0099ff;

        Dialog dialog = new Dialog(activity);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 18));
        card.setBackground(roundRect(surface, dp(activity, 22)));

        TextView title = text(activity, "转发语音", 21, primaryText, true);
        card.addView(title);

        TextView target = text(activity, "发送给  " + targetLabel, 17, primaryText, false);
        target.setPadding(0, dp(activity, 14), 0, 0);
        card.addView(target);

        TextView hint = text(activity, "将作为 QQ 原生语音消息发送", 13, secondaryText, false);
        hint.setPadding(0, dp(activity, 7), 0, dp(activity, 20));
        card.addView(hint);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        TextView cancel = action(activity, "取消", primaryText, secondarySurface);
        TextView send = action(activity, "发送", Color.WHITE, qqBlue);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0, dp(activity, 48), 1f);
        actions.addView(cancel, buttonParams);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                0, dp(activity, 48), 1f);
        sendParams.setMarginStart(dp(activity, 12));
        actions.addView(send, sendParams);
        card.addView(actions);

        cancel.setOnClickListener(view -> dialog.dismiss());
        send.setOnClickListener(view -> {
            send.setEnabled(false);
            send.setAlpha(0.65f);
            int success = 0;
            for (Object forwardTarget : targets) {
                try {
                    Object value = sendVoice.invoke(delegate, file, duration, forwardTarget);
                    if (Boolean.TRUE.equals(value)) {
                        success++;
                    }
                } catch (Throwable throwable) {
                    HookLog.error("发送转发语音失败", unwrap(throwable));
                }
            }
            if (success == targets.size()) {
                showToast(activity, "语音已发送");
                dialog.dismiss();
                activity.finish();
            } else {
                showToast(activity, "发送完成 " + success + "/" + targets.size());
                send.setEnabled(true);
                send.setAlpha(1f);
            }
        });

        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.48f;
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            params.width = Math.min(screenWidth - dp(activity, 40), dp(activity, 430));
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
    }

    private static TextView action(Context context, String value, int textColor, int background) {
        TextView view = text(context, value, 16, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, 0);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(roundRect(background, dp(context, 13)));
        return view;
    }

    private static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void showToast(Activity activity, String text) {
        if (activity != null) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable.getCause();
        return cause == null ? throwable : cause;
    }
}
