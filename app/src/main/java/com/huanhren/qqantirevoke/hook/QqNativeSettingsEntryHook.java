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
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Inserts a real QQ setting item directly below “账号与安全”. */
final class QqNativeSettingsEntryHook {
    private static final String TITLE = "QQ 防撤回 NT";
    private static final int ITEM_ID = 0x51A04001;
    private static final String[] PROVIDERS = {
            "com.tencent.mobileqq.setting.main.MainSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.NewSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.b"
    };

    private final Context applicationContext;
    private final ClassLoader classLoader;
    private final PreferenceReader preferences;

    QqNativeSettingsEntryHook(Context context, ClassLoader classLoader,
            PreferenceReader preferences) {
        this.applicationContext = context.getApplicationContext();
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        int installed = 0;
        for (String className : PROVIDERS) {
            Class<?> provider = XposedHelpers.findClassIfExists(className, classLoader);
            if (provider == null) {
                continue;
            }
            for (Method method : provider.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!List.class.isAssignableFrom(method.getReturnType())
                        || parameters.length != 1
                        || !Context.class.isAssignableFrom(parameters[0])) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook(80) {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            inject(param);
                        }
                    });
                    installed++;
                } catch (Throwable throwable) {
                    HookLog.error("Hook QQ 设置数据源失败", throwable);
                }
            }
        }
        return installed;
    }

    @SuppressWarnings("unchecked")
    private void inject(XC_MethodHook.MethodHookParam param) {
        try {
            if (!preferences.read().qqSettingsEntry()) {
                return;
            }
            Object value = param.getResult();
            if (!(value instanceof List<?>) || param.args == null || param.args.length == 0
                    || !(param.args[0] instanceof Context)) {
                return;
            }
            List<Object> groups = (List<Object>) value;
            if (groups.isEmpty() || containsTitle(groups, TITLE, 0, new IdentityHashMap<>())) {
                return;
            }

            Context context = (Context) param.args[0];
            GroupSample sample = findAccountGroup(groups);
            if (sample == null) {
                return;
            }
            Object entry = createSimpleItem(context, sample.item.getClass());
            if (entry == null) {
                return;
            }

            ArrayList<Object> newItems = new ArrayList<>(sample.items);
            int insertion = Math.min(sample.itemIndex + 1, newItems.size());
            newItems.add(insertion, entry);

            Object rebuilt = rebuildGroup(sample.group, newItems);
            ArrayList<Object> newGroups = new ArrayList<>(groups);
            if (rebuilt != null) {
                newGroups.set(sample.groupIndex, rebuilt);
                param.setResult(newGroups);
                return;
            }
            if (replaceListField(sample.group, sample.listField, newItems)) {
                param.setResult(newGroups);
            }
        } catch (Throwable throwable) {
            HookLog.error("插入 QQ 原生设置入口失败", throwable);
        }
    }

    private GroupSample findAccountGroup(List<Object> groups) {
        GroupSample fallback = null;
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            Object group = groups.get(groupIndex);
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
                        Object listValue = field.get(group);
                        if (!(listValue instanceof List<?>)) {
                            continue;
                        }
                        List<?> items = (List<?>) listValue;
                        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                            Object item = items.get(itemIndex);
                            if (item == null) {
                                continue;
                            }
                            String title = readTitle(item);
                            if (title == null || title.isEmpty()) {
                                continue;
                            }
                            GroupSample current = new GroupSample(
                                    groupIndex,
                                    group,
                                    field,
                                    new ArrayList<>((List<Object>) items),
                                    itemIndex,
                                    item
                            );
                            if (title.contains("账号与安全")) {
                                return current;
                            }
                            if (fallback == null && (title.contains("关于QQ与帮助")
                                    || title.contains("通用"))) {
                                fallback = current;
                            }
                        }
                    } catch (Throwable ignored) {
                        // Continue through other list fields.
                    }
                }
            }
        }
        return fallback;
    }

    private Object createSimpleItem(Context context, Class<?> itemClass) {
        Constructor<?>[] constructors = itemClass.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        int icon = resolveIcon(context);
        for (Constructor<?> constructor : constructors) {
            Class<?>[] types = constructor.getParameterTypes();
            if ((types.length != 4 && types.length != 5)
                    || !Context.class.isAssignableFrom(types[0])
                    || types[1] != int.class
                    || !CharSequence.class.isAssignableFrom(types[2])
                    || types[3] != int.class
                    || (types.length == 5 && types[4] != String.class)) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                Object entry = types.length == 5
                        ? constructor.newInstance(context, ITEM_ID, TITLE, icon, null)
                        : constructor.newInstance(context, ITEM_ID, TITLE, icon);
                if (installClick(entry)) {
                    return entry;
                }
            } catch (Throwable ignored) {
                // Try another constructor.
            }
        }
        return null;
    }

    private boolean installClick(Object entry) {
        ArrayList<Method> candidates = new ArrayList<>();
        for (Class<?> cursor = entry.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getReturnType() == void.class
                        && types.length == 1
                        && "kotlin.jvm.functions.Function0".equals(types[0].getName())) {
                    candidates.add(method);
                }
            }
        }
        candidates.sort(Comparator.comparing(Method::getName));
        for (Method method : candidates) {
            try {
                method.setAccessible(true);
                method.invoke(entry, createFunction0(method.getParameterTypes()[0]));
                return true;
            } catch (Throwable ignored) {
                // Try the next Function0 setter.
            }
        }
        return installFunctionField(entry);
    }

    private boolean installFunctionField(Object entry) {
        for (Class<?> cursor = entry.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !"kotlin.jvm.functions.Function0".equals(field.getType().getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(entry, createFunction0(field.getType()));
                    return true;
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return false;
    }

    private Object createFunction0(Class<?> functionType) throws ReflectiveOperationException {
        Class<?> unitClass = Class.forName("kotlin.Unit", false, classLoader);
        Object unit = unitClass.getField("INSTANCE").get(null);
        ClassLoader proxyLoader = functionType.getClassLoader() == null
                ? classLoader
                : functionType.getClassLoader();
        return Proxy.newProxyInstance(proxyLoader, new Class<?>[]{functionType},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "invoke":
                            openModuleSettings();
                            return unit;
                        case "toString":
                            return "QQAntiRevokeSettingsEntry";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && args.length == 1 && proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private Object rebuildGroup(Object group, List<Object> items) {
        Constructor<?>[] constructors = group.getClass().getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        for (Constructor<?> constructor : constructors) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 0 || !List.class.isAssignableFrom(types[0])) {
                continue;
            }
            Object[] args = new Object[types.length];
            args[0] = items;
            boolean supported = true;
            for (int index = 1; index < types.length; index++) {
                Class<?> type = types[index];
                if (CharSequence.class.isAssignableFrom(type) || type == String.class) {
                    args[index] = "";
                } else if (type == int.class || type == Integer.class) {
                    args[index] = 6;
                } else if (type == boolean.class || type == Boolean.class) {
                    args[index] = false;
                } else if (type.getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) {
                    args[index] = null;
                } else if (!type.isPrimitive()) {
                    args[index] = null;
                } else {
                    supported = false;
                    break;
                }
            }
            if (!supported) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            } catch (Throwable ignored) {
                // Try another group constructor.
            }
        }
        return null;
    }

    private boolean replaceListField(Object group, Field field, List<Object> items) {
        try {
            field.setAccessible(true);
            field.set(group, items);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int resolveIcon(Context context) {
        String[] names = {"qui_tuning", "qui_setting", "qui_profilecard_icon_setting"};
        for (String name : names) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id != 0) {
                return id;
            }
        }
        return android.R.drawable.ic_menu_preferences;
    }

    private void openModuleSettings() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    ModulePrefs.MODULE_PACKAGE,
                    ModulePrefs.MODULE_PACKAGE + ".MainActivity"
            ));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            applicationContext.startActivity(intent);
        } catch (Throwable throwable) {
            HookLog.error("从 QQ 设置打开模块页面失败", throwable);
        }
    }

    private static String readTitle(Object item) {
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

    private static boolean containsTitle(Object value, String expected, int depth,
            IdentityHashMap<Object, Boolean> visited) {
        if (value == null || depth > 5 || visited.put(value, Boolean.TRUE) != null) {
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

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static final class GroupSample {
        final int groupIndex;
        final Object group;
        final Field listField;
        final List<Object> items;
        final int itemIndex;
        final Object item;

        GroupSample(int groupIndex, Object group, Field listField, List<Object> items,
                int itemIndex, Object item) {
            this.groupIndex = groupIndex;
            this.group = group;
            this.listField = listField;
            this.items = items;
            this.itemIndex = itemIndex;
            this.item = item;
        }
    }
}
