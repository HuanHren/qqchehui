package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds a real “转发” item to QQ 9.2.10's PTT menu. */
final class PttVoiceMenuInjectionHook {
    private static final String BASE_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent";
    private static final String PTT_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent";
    private static final String AIO_MSG_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";
    private static final String FORWARD_ACTIVITY =
            "com.tencent.mobileqq.activity.ForwardRecentActivity";

    private static final String EXTRA_PATH = "ptt_forward_path";
    private static final String EXTRA_DURATION = "qqantirevoke_ptt_duration";

    private final Context applicationContext;
    private final ClassLoader classLoader;
    private final PreferenceReader preferences;
    private final Set<Class<?>> hookedClasses =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    PttVoiceMenuInjectionHook(Context context, ClassLoader classLoader,
            PreferenceReader preferences) {
        this.applicationContext = context.getApplicationContext();
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        Class<?> base = XposedHelpers.findClassIfExists(BASE_COMPONENT, classLoader);
        Class<?> msgClass = XposedHelpers.findClassIfExists(AIO_MSG_ITEM, classLoader);
        if (base == null || msgClass == null) {
            HookLog.info("v3.2.1 新增语音转发菜单失败：未找到 BaseContentComponent/AIOMsgItem");
            return 0;
        }

        Method getMsg = null;
        Method getMenu = null;
        for (Method method : base.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            if (msgClass.isAssignableFrom(method.getReturnType())) {
                getMsg = method;
            } else if (List.class.isAssignableFrom(method.getReturnType())
                    && Modifier.isAbstract(method.getModifiers())) {
                getMenu = method;
            }
        }
        if (getMsg == null || getMenu == null) {
            HookLog.info("v3.2.1 新增语音转发菜单失败：未识别消息/菜单方法");
            return 0;
        }

        getMsg.setAccessible(true);
        Method finalGetMsg = getMsg;
        String menuMethodName = getMenu.getName();
        XposedBridge.hookAllConstructors(base, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object component = param.thisObject;
                if (component == null || !PTT_COMPONENT.equals(component.getClass().getName())) {
                    return;
                }
                Class<?> componentClass = component.getClass();
                if (!hookedClasses.add(componentClass)) {
                    return;
                }
                try {
                    Method menuMethod = componentClass.getMethod(menuMethodName);
                    menuMethod.setAccessible(true);
                    XposedBridge.hookMethod(menuMethod, new XC_MethodHook(65) {
                        @Override
                        protected void afterHookedMethod(MethodHookParam menuParam) {
                            appendMenu(menuParam, finalGetMsg);
                        }
                    });
                    HookLog.info("v3.2.1 已安装语音菜单直接新增 Hook："
                            + componentClass.getName() + "#" + menuMethodName);
                } catch (Throwable throwable) {
                    HookLog.error("安装 v3.2.1 语音菜单直接新增 Hook 失败", throwable);
                }
            }
        });
        return 1;
    }

    @SuppressWarnings("unchecked")
    private void appendMenu(XC_MethodHook.MethodHookParam param, Method getMsg) {
        try {
            if (!preferences.read().pttForward()) {
                return;
            }
            Object result = param.getResult();
            if (!(result instanceof List<?>)) {
                return;
            }
            List<Object> items = (List<Object>) result;
            Object msgItem = getMsg.invoke(param.thisObject);
            VoicePayload payload = extractVoicePayload(msgItem);
            if (payload == null) {
                HookLog.debug("v3.2.1 未从语音消息对象提取到 PttElement");
                return;
            }
            Activity activity = findActivity(param.thisObject);
            boolean added = NtPttMenuFactory.appendForwardItem(
                    items,
                    msgItem,
                    () -> launchForwardPicker(activity, payload),
                    classLoader
            );
            if (!added) {
                HookLog.info("v3.2.1 语音菜单已命中，但新增“转发”按钮失败");
            }
        } catch (Throwable throwable) {
            HookLog.error("v3.2.1 处理语音菜单失败", throwable);
        }
    }

    private VoicePayload extractVoicePayload(Object msgItem) {
        if (msgItem == null) {
            return null;
        }
        try {
            Object ptt = null;
            for (Class<?> cursor = msgItem.getClass(); cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                for (Method method : cursor.getDeclaredMethods()) {
                    if (method.getParameterCount() != 0
                            || !method.getReturnType().getSimpleName().contains("PttElement")) {
                        continue;
                    }
                    method.setAccessible(true);
                    ptt = method.invoke(msgItem);
                    if (ptt != null) {
                        break;
                    }
                }
                if (ptt != null) {
                    break;
                }
            }
            if (ptt == null) {
                for (Field field : allFields(msgItem.getClass())) {
                    if (!field.getType().getSimpleName().contains("PttElement")) {
                        continue;
                    }
                    field.setAccessible(true);
                    ptt = field.get(msgItem);
                    if (ptt != null) {
                        break;
                    }
                }
            }
            if (ptt == null) {
                return null;
            }
            String path = stringGetter(ptt, "getFilePath", "filePath");
            int duration = intGetter(ptt, "getDuration", "duration");
            if (path == null || path.trim().isEmpty()) {
                return null;
            }
            return new VoicePayload(path, duration);
        } catch (Throwable throwable) {
            HookLog.error("提取 PttElement 失败", throwable);
            return null;
        }
    }

    private void launchForwardPicker(Activity activity, VoicePayload payload) {
        Activity target = activity != null ? activity : currentActivity();
        Context context = target != null ? target : applicationContext;
        File file = new File(payload.path);
        if (!file.isFile()) {
            HookLog.info("语音转发未启动：文件不存在，请先播放语音，path=" + payload.path);
            return;
        }
        try {
            Class<?> activityClass = Class.forName(FORWARD_ACTIVITY, false, classLoader);
            Intent intent = new Intent(context, activityClass);
            intent.putExtra("selection_mode", 0);
            intent.putExtra("direct_send_if_dataline_forward", false);
            intent.putExtra("forward_text", "null");
            intent.putExtra(EXTRA_PATH, file.getAbsolutePath());
            intent.putExtra(EXTRA_DURATION, payload.durationMs);
            intent.putExtra("forward_type", -1);
            intent.putExtra("caller_name", "ChatActivity");
            intent.putExtra("k_smartdevice", false);
            intent.putExtra("k_dataline", false);
            intent.putExtra("k_forward_title", "语音转发");
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            HookLog.info("已从新增菜单打开 QQ 联系人选择器，path=" + file.getAbsolutePath());
        } catch (Throwable throwable) {
            HookLog.error("新增菜单打开 QQ 联系人选择器失败", throwable);
        }
    }

    private Activity findActivity(Object value) {
        if (value instanceof Activity) {
            return (Activity) value;
        }
        if (value instanceof ContextWrapper) {
            Context context = ((ContextWrapper) value).getBaseContext();
            if (context instanceof Activity) {
                return (Activity) context;
            }
        }
        if (value != null) {
            for (Field field : allFields(value.getClass())) {
                try {
                    field.setAccessible(true);
                    Object candidate = field.get(value);
                    if (candidate instanceof Activity) {
                        return (Activity) candidate;
                    }
                    if (candidate instanceof ContextWrapper) {
                        Context context = ((ContextWrapper) candidate).getBaseContext();
                        if (context instanceof Activity) {
                            return (Activity) context;
                        }
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return currentActivity();
    }

    @SuppressWarnings("unchecked")
    private Activity currentActivity() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            Object activities = XposedHelpers.getObjectField(thread, "mActivities");
            if (activities instanceof Map<?, ?>) {
                for (Object record : ((Map<Object, Object>) activities).values()) {
                    boolean paused = XposedHelpers.getBooleanField(record, "paused");
                    Object activity = XposedHelpers.getObjectField(record, "activity");
                    if (!paused && activity instanceof Activity) {
                        return (Activity) activity;
                    }
                }
            }
        } catch (Throwable ignored) {
            // No foreground activity.
        }
        return null;
    }

    private static List<Field> allFields(Class<?> type) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            Collections.addAll(fields, cursor.getDeclaredFields());
        }
        return fields;
    }

    private static String stringGetter(Object target, String methodName, String fieldName) {
        try {
            Object value = XposedHelpers.callMethod(target, methodName);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            try {
                Object value = XposedHelpers.getObjectField(target, fieldName);
                return value == null ? null : value.toString();
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static int intGetter(Object target, String methodName, String fieldName) {
        try {
            Object value = XposedHelpers.callMethod(target, methodName);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            try {
                return XposedHelpers.getIntField(target, fieldName);
            } catch (Throwable ignoredAgain) {
                return 0;
            }
        }
    }

    private static final class VoicePayload {
        final String path;
        final int durationMs;

        VoicePayload(String path, int durationMs) {
            this.path = path;
            this.durationMs = durationMs;
        }
    }
}
