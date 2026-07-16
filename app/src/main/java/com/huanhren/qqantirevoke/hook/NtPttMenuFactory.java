package com.huanhren.qqantirevoke.hook;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.android.AndroidClassLoadingStrategy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

/** Creates a real QQ NT long-press menu item for PTT messages at runtime. */
final class NtPttMenuFactory {
    private static final String TITLE = "转发";
    private static final Map<Object, Runnable> ACTIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Class<?> generatedMenuClass;
    private static volatile Constructor<?> generatedConstructor;

    private NtPttMenuFactory() {}

    static boolean appendForwardItem(List<Object> items, Object msgItem, Runnable action,
            ClassLoader hostClassLoader) {
        if (items == null || items.isEmpty() || msgItem == null || action == null) {
            HookLog.info("无法新增语音转发菜单：菜单为空或消息对象为空");
            return false;
        }
        try {
            ensureGenerated(items, msgItem, hostClassLoader);
            Constructor<?> constructor = generatedConstructor;
            Object menuItem = constructor.getParameterCount() == 1
                    ? constructor.newInstance(msgItem)
                    : constructor.newInstance();
            ACTIONS.put(menuItem, action);
            items.add(menuItem);
            HookLog.info("已向 QQ 语音长按菜单新增“转发”按钮，menuClass="
                    + generatedMenuClass.getName());
            return true;
        } catch (Throwable throwable) {
            HookLog.error("动态创建 QQ NT 语音转发菜单失败", throwable);
            return false;
        }
    }

    private static synchronized void ensureGenerated(List<Object> items, Object msgItem,
            ClassLoader hostClassLoader) throws Exception {
        if (generatedMenuClass != null && generatedConstructor != null) {
            return;
        }
        Class<?> baseClass = findAbstractMenuBase(items, msgItem.getClass());
        if (baseClass == null) {
            throw new IllegalStateException("未识别 QQ NT 菜单抽象基类");
        }

        Method titleMethod = null;
        Method clickMethod = null;
        for (Class<?> cursor = baseClass; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                    continue;
                }
                if (titleMethod == null && method.getReturnType() == String.class) {
                    titleMethod = method;
                } else if (clickMethod == null && method.getReturnType() == void.class) {
                    clickMethod = method;
                }
            }
        }
        if (titleMethod == null || clickMethod == null) {
            throw new IllegalStateException("菜单基类缺少标题或点击方法: " + baseClass.getName());
        }

        DynamicType.Builder<?> builder = new ByteBuddy()
                .subclass(baseClass)
                .method(ElementMatchers.isAbstract())
                .intercept(StubMethod.INSTANCE)
                .method(ElementMatchers.named(titleMethod.getName())
                        .and(ElementMatchers.takesArguments(0)))
                .intercept(FixedValue.value(TITLE))
                .method(ElementMatchers.named(clickMethod.getName())
                        .and(ElementMatchers.takesArguments(0)))
                .intercept(MethodDelegation.to(ClickDispatcher.class));

        Application application = currentApplication();
        if (application == null) {
            throw new IllegalStateException("ActivityThread.currentApplication() is null");
        }
        File generatedDir = application.getDir("qqantirevoke_generated", Context.MODE_PRIVATE);
        generatedMenuClass = builder.make()
                .load(hostClassLoader, new AndroidClassLoadingStrategy.Injecting(generatedDir))
                .getLoaded();
        generatedConstructor = findUsableConstructor(generatedMenuClass, msgItem.getClass());
        generatedConstructor.setAccessible(true);
        HookLog.info("已生成 QQ NT 自定义菜单类，base=" + baseClass.getName()
                + "，titleMethod=" + titleMethod.getName()
                + "，clickMethod=" + clickMethod.getName()
                + "，generatedDir=" + generatedDir.getAbsolutePath());
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable throwable) {
            HookLog.error("反射获取 QQ Application 失败", throwable);
            return null;
        }
    }

    private static Class<?> findAbstractMenuBase(List<Object> items, Class<?> msgClass) {
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            for (Class<?> cursor = item.getClass(); cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                if (Modifier.isAbstract(cursor.getModifiers()) && hasUsableConstructor(cursor, msgClass)) {
                    return cursor;
                }
            }
        }
        return null;
    }

    private static boolean hasUsableConstructor(Class<?> type, Class<?> msgClass) {
        try {
            findUsableConstructor(type, msgClass);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static Constructor<?> findUsableConstructor(Class<?> type, Class<?> msgClass)
            throws NoSuchMethodException {
        Constructor<?> noArg = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isAssignableFrom(msgClass)) {
                return constructor;
            }
            if (parameters.length == 0) {
                noArg = constructor;
            }
        }
        if (noArg != null) {
            return noArg;
        }
        throw new NoSuchMethodException("没有可用构造方法: " + type.getName());
    }

    public static final class ClickDispatcher {
        private ClickDispatcher() {}

        public static void dispatch(@This Object menuItem) {
            Runnable action = ACTIONS.remove(menuItem);
            if (action != null) {
                action.run();
            } else {
                HookLog.info("语音转发菜单点击时未找到绑定动作");
            }
        }
    }
}
