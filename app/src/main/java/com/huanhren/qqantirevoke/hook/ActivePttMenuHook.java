package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Actively adds a new "转发" item to QQ 9.2.10's NT PTT long-press menu.
 *
 * <p>v3.2 only searched for an existing Forward item. QQ 9.2.10 does not expose one for
 * PTT, so nothing was added. This implementation clones one of QQ's own menu item objects,
 * overrides only the cloned object's title/id/click methods, and appends it to the returned
 * menu list.</p>
 */
final class ActivePttMenuHook {
    private static final String BASE_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent";
    private static final String PTT_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent";
    private static final String AIO_MSG_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";
    private static final String FORWARD_ACTIVITY =
            "com.tencent.mobileqq.activity.ForwardRecentActivity";
    private static final String EXTRA_PATH = "ptt_forward_path";
    private static final String EXTRA_DURATION = "qqantirevoke_ptt_duration";
    private static final int MENU_ID = 0x51A03301;

    private final Context applicationContext;
    private final ClassLoader classLoader;
    private final PreferenceReader preferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Class<?>> hookedComponents =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Method> hookedMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Object, VoiceAction> actions =
            Collections.synchronizedMap(new IdentityHashMap<>());

    ActivePttMenuHook(Context context, ClassLoader classLoader, PreferenceReader preferences) {
        this.applicationContext = context.getApplicationContext();
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        Class<?> base = XposedHelpers.findClassIfExists(BASE_COMPONENT, classLoader);
        Class<?> msgClass = XposedHelpers.findClassIfExists(AIO_MSG_ITEM, classLoader);
        if (base == null || msgClass == null) {
            HookLog.info("主动语音转发菜单未安装：BaseContentComponent/AIOMsgItem 不存在");
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
            HookLog.info("主动语音转发菜单未安装：未识别消息或菜单方法");
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
                if (!hookedComponents.add(componentClass)) {
                    return;
                }
                try {
                    Method menuMethod = componentClass.getMethod(menuMethodName);
                    menuMethod.setAccessible(true);
                    XposedBridge.hookMethod(menuMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam menuParam) {
                            injectForwardItem(menuParam, finalGetMsg);
                        }
                    });
                    HookLog.info("已安装主动语音转发菜单注入："
                            + componentClass.getName() + "#" + menuMethodName);
                } catch (Throwable throwable) {
                    HookLog.error("安装主动语音转发菜单失败", throwable);
                }
            }
        });
        return 1;
    }

    @SuppressWarnings("unchecked")
    private void injectForwardItem(XC_MethodHook.MethodHookParam param, Method getMsg) {
        try {
            if (!preferences.read().pttForward()) {
                return;
            }
            Object result = param.getResult();
            if (!(result instanceof List<?>)) {
                return;
            }
            Object msgItem = getMsg.invoke(param.thisObject);
            VoicePayload payload = extractVoicePayload(msgItem);
            if (payload == null) {
                return;
            }

            List<Object> original = (List<Object>) result;
            for (Object item : original) {
                if (item != null && "转发".equals(readMenuTitle(item))) {
                    return;
                }
            }
            Object sample = chooseSampleItem(original);
            if (sample == null) {
                HookLog.info("主动语音转发菜单失败：QQ 菜单列表为空");
                return;
            }

            Object cloned = cloneMenuItem(sample, msgItem);
            if (cloned == null) {
                HookLog.info("主动语音转发菜单失败：无法复制 QQ 菜单项 "
                        + sample.getClass().getName());
                return;
            }
            String sampleTitle = readMenuTitle(sample);
            rewriteTitleFields(cloned, sampleTitle, "转发");
            hookMenuItemClass(sample, cloned.getClass());

            Activity activity = findActivity(param.thisObject);
            actions.put(cloned, new VoiceAction(activity, payload));
            ArrayList<Object> copy = new ArrayList<>(original);
            int index = Math.min(2, copy.size());
            copy.add(index, cloned);
            param.setResult(copy);
            HookLog.info("已主动添加语音“转发”菜单：itemClass="
                    + cloned.getClass().getName() + ", path=" + payload.filePath);
        } catch (Throwable throwable) {
            HookLog.error("主动添加语音转发菜单失败", throwable);
        }
    }

    private Object chooseSampleItem(List<Object> items) {
        Object fallback = null;
        for (Object item : items) {
            if (item == null || Modifier.isAbstract(item.getClass().getModifiers())) {
                continue;
            }
            String title = readMenuTitle(item);
            if (title != null && (title.contains("截图") || title.contains("提醒")
                    || title.contains("收藏") || title.contains("多选"))) {
                return item;
            }
            if (fallback == null && title != null) {
                fallback = item;
            }
        }
        return fallback;
    }

    private Object cloneMenuItem(Object sample, Object msgItem) {
        Class<?> type = sample.getClass();
        Object clone = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 1 && msgItem != null
                        && parameters[0].isAssignableFrom(msgItem.getClass())) {
                    clone = constructor.newInstance(msgItem);
                    break;
                }
                if (parameters.length == 0) {
                    clone = constructor.newInstance();
                    break;
                }
            } catch (Throwable ignored) {
                // Try another constructor or Unsafe below.
            }
        }
        if (clone == null) {
            clone = allocateWithoutConstructor(type);
        }
        if (clone == null) {
            return null;
        }
        copyInstanceFields(sample, clone);
        return clone;
    }

    private Object allocateWithoutConstructor(Class<?> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, type);
        } catch (Throwable throwable) {
            HookLog.debug("Unsafe 复制菜单项不可用：" + throwable);
            return null;
        }
    }

    private void copyInstanceFields(Object source, Object target) {
        for (Class<?> cursor = source.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(target, field.get(source));
                } catch (Throwable ignored) {
                    // Some final/runtime fields may not be writable; constructor state remains.
                }
            }
        }
    }

    private void rewriteTitleFields(Object target, String oldTitle, String newTitle) {
        if (oldTitle == null) {
            return;
        }
        for (Class<?> cursor = target.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !(field.getType() == String.class
                        || CharSequence.class.isAssignableFrom(field.getType()))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null && oldTitle.equals(value.toString())) {
                        field.set(target, newTitle);
                    }
                } catch (Throwable ignored) {
                    // Getter hook below remains the main mechanism.
                }
            }
        }
    }

    private void hookMenuItemClass(Object sample, Class<?> itemClass) {
        Set<String> abstractTitleNames = new HashSet<>();
        Set<String> abstractIntNames = new HashSet<>();
        Set<String> abstractClickNames = new HashSet<>();
        for (Class<?> cursor = itemClass.getSuperclass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            if (!Modifier.isAbstract(cursor.getModifiers())) {
                continue;
            }
            for (Method method : cursor.getDeclaredMethods()) {
                if (!Modifier.isAbstract(method.getModifiers()) || method.getParameterCount() != 0) {
                    continue;
                }
                if (method.getReturnType() == void.class) {
                    abstractClickNames.add(method.getName());
                } else if (method.getReturnType() == int.class) {
                    abstractIntNames.add(method.getName());
                } else if (CharSequence.class.isAssignableFrom(method.getReturnType())) {
                    abstractTitleNames.add(method.getName());
                }
            }
        }

        if (abstractTitleNames.isEmpty()) {
            for (Method method : allMethods(itemClass)) {
                if (method.getParameterCount() == 0
                        && CharSequence.class.isAssignableFrom(method.getReturnType())) {
                    abstractTitleNames.add(method.getName());
                }
            }
        }
        if (abstractClickNames.isEmpty()) {
            ArrayList<String> candidates = new ArrayList<>();
            for (Method method : allMethods(itemClass)) {
                if (!Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class) {
                    candidates.add(method.getName());
                }
            }
            if (candidates.size() == 1) {
                abstractClickNames.add(candidates.get(0));
            }
        }

        for (String name : abstractTitleNames) {
            Method method = findNoArgMethod(itemClass, name);
            if (method == null || !hookedMethods.add(method)) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (actions.containsKey(param.thisObject)) {
                        param.setResult("转发");
                    }
                }
            });
        }

        for (String name : abstractIntNames) {
            Method method = findNoArgMethod(itemClass, name);
            if (method == null || !hookedMethods.add(method)) {
                continue;
            }
            method.setAccessible(true);
            int original = invokeInt(method, sample, MENU_ID);
            boolean icon = isDrawableResource(original);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (actions.containsKey(param.thisObject)) {
                        param.setResult(icon ? original : MENU_ID);
                    }
                }
            });
        }

        for (String name : abstractClickNames) {
            Method method = findNoArgMethod(itemClass, name);
            if (method == null || !hookedMethods.add(method)) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook(100) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    VoiceAction action = actions.remove(param.thisObject);
                    if (action == null) {
                        return;
                    }
                    param.setResult(null);
                    Activity activity = action.activity == null ? null : action.activity.get();
                    launchForwardPicker(activity, action.payload);
                }
            });
        }
        HookLog.debug("主动菜单方法已接管：title=" + abstractTitleNames
                + ", int=" + abstractIntNames + ", click=" + abstractClickNames);
    }

    private List<Method> allMethods(Class<?> type) {
        ArrayList<Method> result = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            Collections.addAll(result, cursor.getDeclaredMethods());
        }
        return result;
    }

    private Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name);
                if (method.getParameterCount() == 0) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Continue upward.
            }
        }
        return null;
    }

    private int invokeInt(Method method, Object target, int fallback) {
        try {
            Object value = method.invoke(target);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean isDrawableResource(int value) {
        if (value == 0) {
            return false;
        }
        try {
            String type = applicationContext.getResources().getResourceTypeName(value);
            return "drawable".equals(type) || "mipmap".equals(type);
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    private String readMenuTitle(Object item) {
        if (item == null) {
            return null;
        }
        for (Method method : allMethods(item.getClass())) {
            if (method.getParameterCount() != 0
                    || !CharSequence.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(item);
                if (value != null && !value.toString().trim().isEmpty()) {
                    return value.toString();
                }
            } catch (Throwable ignored) {
                // Continue.
            }
        }
        return null;
    }

    private VoicePayload extractVoicePayload(Object msgItem) {
        if (msgItem == null) {
            return null;
        }
        Object ptt = findPttElement(msgItem);
        if (ptt == null) {
            return null;
        }
        String path = readString(ptt, "getFilePath", "filePath", "path");
        if (path == null || path.isEmpty()) {
            return null;
        }
        long duration = readLong(ptt, "getDuration", "duration");
        int durationMs = duration <= 0 ? 1000
                : duration < 1000 ? (int) Math.min(Integer.MAX_VALUE, duration * 1000L)
                : (int) Math.min(Integer.MAX_VALUE, duration);
        return new VoicePayload(path, durationMs);
    }

    private Object findPttElement(Object msgItem) {
        for (Class<?> cursor = msgItem.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && "PttElement".equals(method.getReturnType().getSimpleName())) {
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(msgItem);
                        if (value != null) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // Continue.
                    }
                }
            }
            for (Field field : cursor.getDeclaredFields()) {
                if (!"PttElement".equals(field.getType().getSimpleName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(msgItem);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return null;
    }

    private String readString(Object target, String methodName, String... fields) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value != null) {
                return value.toString();
            }
        } catch (Throwable ignored) {
            // Fields below.
        }
        for (String name : fields) {
            try {
                Field field = findField(target.getClass(), name);
                Object value = field.get(target);
                if (value != null) {
                    return value.toString();
                }
            } catch (Throwable ignored) {
                // Continue.
            }
        }
        return null;
    }

    private long readLong(Object target, String methodName, String... fields) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
            // Fields below.
        }
        for (String name : fields) {
            try {
                Field field = findField(target.getClass(), name);
                Object value = field.get(target);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (Throwable ignored) {
                // Continue.
            }
        }
        return 0L;
    }

    private void launchForwardPicker(Activity activity, VoicePayload payload) {
        Runnable action = () -> {
            Context context = activity != null ? activity : applicationContext;
            File file = new File(payload.filePath);
            if (!file.isFile()) {
                Toast.makeText(context, "语音尚未下载，请先播放一次", Toast.LENGTH_SHORT).show();
                HookLog.info("主动语音转发未启动：文件不存在，path=" + payload.filePath);
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
                HookLog.info("主动语音菜单已打开联系人选择器：path=" + file.getAbsolutePath());
            } catch (Throwable throwable) {
                HookLog.error("主动语音菜单打开联系人选择器失败", throwable);
                Toast.makeText(context, "无法打开 QQ 转发页面", Toast.LENGTH_SHORT).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private Activity findActivity(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Activity) {
            return (Activity) source;
        }
        if (source instanceof Context) {
            Activity activity = unwrapActivity((Context) source);
            if (activity != null) {
                return activity;
            }
        }
        for (Class<?> cursor = source.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(source);
                    if (value instanceof Activity) {
                        return (Activity) value;
                    }
                    if (value instanceof Context) {
                        Activity activity = unwrapActivity((Context) value);
                        if (activity != null) {
                            return activity;
                        }
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return null;
    }

    private Activity unwrapActivity(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (current instanceof ContextWrapper) {
                Context next = ((ContextWrapper) current).getBaseContext();
                if (next == current) {
                    break;
                }
                current = next;
            } else {
                break;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class VoicePayload {
        final String filePath;
        final int durationMs;

        VoicePayload(String filePath, int durationMs) {
            this.filePath = filePath;
            this.durationMs = durationMs;
        }
    }

    private static final class VoiceAction {
        final WeakReference<Activity> activity;
        final VoicePayload payload;

        VoiceAction(Activity activity, VoicePayload payload) {
            this.activity = activity == null ? null : new WeakReference<>(activity);
            this.payload = payload;
        }
    }
}
