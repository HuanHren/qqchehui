package com.huanhren.qqantirevoke.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Inserts a native-looking module item into QQ 9.2.10's settings config provider. */
final class QqSettingsProviderEntryHook {
    private static final String[] PROVIDERS = {
            "com.tencent.mobileqq.setting.main.MainSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.NewSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.b"
    };
    private static final String ENTRY_TITLE = "QQ 防撤回 NT";

    private final Context applicationContext;
    private final ClassLoader classLoader;
    private final PreferenceReader preferences;
    private final Set<Method> hookedProviderMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Method> hookedTitleMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Object, String> clonedTitles =
            Collections.synchronizedMap(new IdentityHashMap<>());

    QqSettingsProviderEntryHook(Context context, ClassLoader classLoader,
            PreferenceReader preferences) {
        this.applicationContext = context.getApplicationContext();
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        int count = 0;
        for (String className : PROVIDERS) {
            Class<?> provider = XposedHelpers.findClassIfExists(className, classLoader);
            if (provider == null) {
                continue;
            }
            for (Method method : provider.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!List.class.isAssignableFrom(method.getReturnType())
                        || parameters.length != 1
                        || !Context.class.isAssignableFrom(parameters[0])
                        || !hookedProviderMethods.add(method)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook(60) {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            injectProviderEntry(param);
                        }
                    });
                    count++;
                    HookLog.info("已 Hook QQ 设置数据提供器：" + method);
                } catch (Throwable throwable) {
                    hookedProviderMethods.remove(method);
                    HookLog.error("Hook QQ 设置数据提供器失败：" + method, throwable);
                }
            }
        }
        if (count == 0) {
            HookLog.info("未找到 QQ 设置数据提供器方法，将保留旧 View 注入备用方案");
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void injectProviderEntry(XC_MethodHook.MethodHookParam param) {
        try {
            if (!preferences.read().qqSettingsEntry()) {
                return;
            }
            Object result = param.getResult();
            if (!(result instanceof List<?>)) {
                return;
            }
            List<Object> groups = (List<Object>) result;
            if (containsTitle(groups, ENTRY_TITLE)) {
                return;
            }
            Sample sample = findSample(groups);
            if (sample == null) {
                HookLog.info("QQ 设置数据入口注入失败：没有找到可复制的原生设置项");
                return;
            }

            Object clone = cloneObject(sample.item);
            if (clone == null) {
                HookLog.info("QQ 设置数据入口注入失败：无法复制 "
                        + sample.item.getClass().getName());
                return;
            }
            rewriteTitleFields(clone, sample.title, ENTRY_TITLE);
            installClickAction(clone);
            hookTitleGetters(sample.item, clone, sample.title);

            Object newGroup = createGroup(sample.group.getClass(), clone);
            if (newGroup != null) {
                ArrayList<Object> copy = new ArrayList<>(groups);
                copy.add(Math.min(2, copy.size()), newGroup);
                param.setResult(copy);
                HookLog.info("已通过 QQ 设置数据提供器插入“" + ENTRY_TITLE + "”入口");
                return;
            }

            if (appendToExistingGroup(sample, clone)) {
                HookLog.info("已追加“" + ENTRY_TITLE + "”到 QQ 原生设置分组");
            } else {
                HookLog.info("QQ 设置入口注入失败：无法构造或修改设置分组");
            }
        } catch (Throwable throwable) {
            HookLog.error("通过设置数据提供器注入模块入口失败", throwable);
        }
    }

    private Sample findSample(List<Object> groups) {
        Sample fallback = null;
        for (Object group : groups) {
            if (group == null) {
                continue;
            }
            for (Class<?> cursor = group.getClass(); cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !List.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(group);
                        if (!(value instanceof List<?>)) {
                            continue;
                        }
                        List<?> items = (List<?>) value;
                        for (Object item : items) {
                            if (item == null) {
                                continue;
                            }
                            String title = readTitle(item);
                            if (title == null || title.isEmpty()) {
                                continue;
                            }
                            Sample candidate = new Sample(group, field, items, item, title);
                            if (title.contains("关于QQ与帮助")) {
                                return candidate;
                            }
                            if (fallback == null || title.contains("通用")) {
                                fallback = candidate;
                            }
                        }
                    } catch (Throwable ignored) {
                        // Continue through group fields.
                    }
                }
            }
        }
        return fallback;
    }

    private boolean containsTitle(Object value, String expected) {
        return containsTitle(value, expected, 0, new IdentityHashMap<>());
    }

    private boolean containsTitle(Object value, String expected, int depth,
            IdentityHashMap<Object, Boolean> visited) {
        if (value == null || depth > 4 || visited.put(value, Boolean.TRUE) != null) {
            return false;
        }
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                if (containsTitle(item, expected, depth + 1, visited)) {
                    return true;
                }
            }
            return false;
        }
        String title = readTitle(value);
        if (expected.equals(title)) {
            return true;
        }
        for (Class<?> cursor = value.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (containsTitle(field.get(value), expected, depth + 1, visited)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return false;
    }

    private Object cloneObject(Object source) {
        Class<?> type = source.getClass();
        Object clone = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 0) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                clone = constructor.newInstance();
                break;
            } catch (Throwable ignored) {
                // Unsafe below.
            }
        }
        if (clone == null) {
            clone = allocateWithoutConstructor(type);
        }
        if (clone == null) {
            return null;
        }
        copyInstanceFields(source, clone);
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
            HookLog.debug("Unsafe 复制设置项不可用：" + throwable);
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
                    // Constructor/default state remains for non-writable fields.
                }
            }
        }
    }

    private void rewriteTitleFields(Object target, String oldTitle, String newTitle) {
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
                    // Getter hook below remains available.
                }
            }
        }
    }

    private void installClickAction(Object clone) {
        boolean installed = false;
        for (Class<?> cursor = clone.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !"kotlin.jvm.functions.Function0".equals(field.getType().getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(clone, createFunction0(field.getType()));
                    installed = true;
                } catch (Throwable throwable) {
                    HookLog.debug("替换设置项 Function0 字段失败：" + throwable);
                }
            }
        }
        if (installed) {
            return;
        }
        for (Method method : clone.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1
                    && "kotlin.jvm.functions.Function0".equals(parameters[0].getName())
                    && method.getReturnType() == void.class) {
                try {
                    method.setAccessible(true);
                    method.invoke(clone, createFunction0(parameters[0]));
                    return;
                } catch (Throwable throwable) {
                    HookLog.debug("调用设置项 Function0 setter 失败：" + throwable);
                }
            }
        }
    }

    private Object createFunction0(Class<?> function0) throws ReflectiveOperationException {
        Class<?> unitClass = Class.forName("kotlin.Unit", false, classLoader);
        Object unit = unitClass.getField("INSTANCE").get(null);
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{function0}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "invoke":
                    openModuleApp();
                    return unit;
                case "toString":
                    return "QQAntiRevokeSettingsEntry";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length == 1 && proxy == args[0];
                default:
                    return null;
            }
        });
    }

    private void hookTitleGetters(Object sample, Object clone, String sampleTitle) {
        clonedTitles.put(clone, ENTRY_TITLE);
        for (Class<?> cursor = sample.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || !CharSequence.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(sample);
                    if (value == null || !sampleTitle.equals(value.toString())
                            || !hookedTitleMethods.add(method)) {
                        continue;
                    }
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String title = clonedTitles.get(param.thisObject);
                            if (title != null) {
                                param.setResult(title);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
    }

    private Object createGroup(Class<?> groupClass, Object clone) {
        ArrayList<Object> items = new ArrayList<>();
        items.add(clone);
        for (Constructor<?> constructor : groupClass.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 3
                        && List.class.isAssignableFrom(parameters[0])
                        && CharSequence.class.isAssignableFrom(parameters[1])
                        && CharSequence.class.isAssignableFrom(parameters[2])) {
                    return constructor.newInstance(items, "", "");
                }
                if (parameters.length == 5
                        && List.class.isAssignableFrom(parameters[0])
                        && CharSequence.class.isAssignableFrom(parameters[1])
                        && CharSequence.class.isAssignableFrom(parameters[2])) {
                    return constructor.newInstance(items, "", "", 6, null);
                }
            } catch (Throwable ignored) {
                // Try another group constructor.
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean appendToExistingGroup(Sample sample, Object clone) {
        try {
            if (sample.items instanceof ArrayList) {
                ((List) sample.items).add(clone);
                return true;
            }
        } catch (Throwable ignored) {
            // Replace the list field below.
        }
        try {
            ArrayList<Object> copy = new ArrayList<>((List<Object>) sample.items);
            copy.add(clone);
            sample.listField.setAccessible(true);
            sample.listField.set(sample.group, copy);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String readTitle(Object item) {
        if (item == null) {
            return null;
        }
        for (Class<?> cursor = item.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
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
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !(field.getType() == String.class
                        || CharSequence.class.isAssignableFrom(field.getType()))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value != null && !value.toString().trim().isEmpty()) {
                        return value.toString();
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return null;
    }

    private void openModuleApp() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    ModulePrefs.MODULE_PACKAGE,
                    ModulePrefs.MODULE_PACKAGE + ".MainActivity"
            ));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            applicationContext.startActivity(intent);
        } catch (Throwable throwable) {
            HookLog.error("从 QQ 设置数据项打开模块 App 失败", throwable);
        }
    }

    private static final class Sample {
        final Object group;
        final Field listField;
        final List<?> items;
        final Object item;
        final String title;

        Sample(Object group, Field listField, List<?> items, Object item, String title) {
            this.group = group;
            this.listField = listField;
            this.items = items;
            this.item = item;
            this.title = title;
        }
    }
}
