package com.huanhren.qqantirevoke.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class LegacyRecallFallbackHook {
    private static final String BASE_MESSAGE_MANAGER =
            "com.tencent.imcore.message.BaseMessageManager";

    private final ClassLoader classLoader;
    private final PreferenceReader preferences;

    LegacyRecallFallbackHook(ClassLoader classLoader, PreferenceReader preferences) {
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        Class<?> manager = XposedHelpers.findClassIfExists(BASE_MESSAGE_MANAGER, classLoader);
        if (manager == null) {
            HookLog.debug("未找到旧消息撤回入口类 " + BASE_MESSAGE_MANAGER);
            return 0;
        }

        int count = 0;
        for (Method method : manager.getDeclaredMethods()) {
            if (!isTarget(method)) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        PreferenceReader.Settings settings = preferences.read();
                        HookLog.setDiagnostics(settings.diagnostics());
                        if (!settings.enabled() || !settings.legacyFallback()) {
                            return;
                        }
                        int itemCount = 0;
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof ArrayList) {
                            itemCount = ((ArrayList<?>) param.args[0]).size();
                        }
                        HookLog.info("旧消息链路触发撤回，已直接阻断入口，itemCount=" + itemCount);
                        param.setResult(null);
                    }
                });
                count++;
                HookLog.info("Hook 旧撤回备用入口 " + signature(method));
            } catch (Throwable throwable) {
                HookLog.error("Hook 旧撤回备用入口失败 " + signature(method), throwable);
            }
        }
        return count;
    }

    private static boolean isTarget(Method method) {
        if (!"k".equals(method.getName())
                || method.isSynthetic()
                || Modifier.isAbstract(method.getModifiers())
                || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 2
                && parameters[0] == ArrayList.class
                && parameters[1] == boolean.class;
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.toString(method.getParameterTypes())
                + " -> " + method.getReturnType().getTypeName();
    }
}
