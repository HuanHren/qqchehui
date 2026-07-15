package com.huanhren.qqantirevoke.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class NtRecallPushHook {
    private static final String WRAPPER_PROXY =
            "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy";

    private final ClassLoader classLoader;
    private final PreferenceReader preferences;

    NtRecallPushHook(ClassLoader classLoader, PreferenceReader preferences) {
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        Class<?> proxy = XposedHelpers.findClassIfExists(WRAPPER_PROXY, classLoader);
        if (proxy == null) {
            HookLog.info("未找到 NT 推送类 " + WRAPPER_PROXY);
            return 0;
        }

        int count = 0;
        for (Method method : proxy.getDeclaredMethods()) {
            if (!isTargetMethod(method)) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        handlePush(param);
                    }
                });
                count++;
                HookLog.info("Hook NT 推送入口 " + signature(method));
            } catch (Throwable throwable) {
                HookLog.error("Hook NT 推送入口失败 " + signature(method), throwable);
            }
        }
        if (count == 0) {
            HookLog.info("找到 NT 推送类，但没有匹配到 onMsfPush(String, byte[], ...)");
        }
        return count;
    }

    private void handlePush(XC_MethodHook.MethodHookParam param) {
        try {
            PreferenceReader.Settings settings = preferences.read();
            HookLog.setDiagnostics(settings.diagnostics());
            if (!settings.enabled() || param.args == null || param.args.length < 2) {
                return;
            }

            String command = param.args[0] instanceof String ? (String) param.args[0] : null;
            byte[] payload = param.args[1] instanceof byte[] ? (byte[]) param.args[1] : null;
            if (command == null || payload == null) {
                return;
            }

            if (NtRecallParser.CMD_OL_PUSH.equals(command)) {
                handleOnlinePush(param, payload, settings);
            } else if (NtRecallParser.CMD_INFO_SYNC.equals(command)) {
                handleInfoSync(param, payload, settings);
            }
        } catch (Throwable throwable) {
            HookLog.error("处理 NT 推送失败，已放行原调用", throwable);
        }
    }

    private void handleOnlinePush(XC_MethodHook.MethodHookParam param, byte[] payload,
            PreferenceReader.Settings settings) {
        NtRecallParser.ParseResult result = NtRecallParser.parseOlPush(payload);
        if (!result.isRecall()) {
            return;
        }

        HookLog.info("检测到 NT 在线撤回推送：" + result.describe());
        if (settings.blockOnlineRecall()) {
            param.setResult(null);
            HookLog.info("已阻断 NT 在线撤回原处理，原消息应继续保留");
        } else {
            HookLog.info("在线撤回阻断开关关闭，仅记录诊断日志");
        }
    }

    private void handleInfoSync(XC_MethodHook.MethodHookParam param, byte[] payload,
            PreferenceReader.Settings settings) {
        NtRecallParser.ParseResult result = NtRecallParser.parseInfoSync(payload);
        if (!result.isRecall()) {
            return;
        }

        HookLog.info("检测到 NT 同步撤回数据：" + result.describe());
        if (!settings.stripSyncRecall()) {
            HookLog.info("同步撤回过滤开关关闭，已放行原数据");
            return;
        }

        try {
            byte[] stripped = NtRecallParser.stripInfoSyncRecall(payload);
            if (stripped != null && stripped.length < payload.length) {
                param.args[1] = stripped;
                HookLog.info("已从 InfoSyncPush 移除 sync_msg_recall 字段，保留其他同步数据"
                        + "（" + payload.length + " -> " + stripped.length + " bytes）");
            } else {
                HookLog.info("同步撤回字段存在，但移除后长度未变化；为安全起见放行原数据");
            }
        } catch (ProtoWire.ParseException exception) {
            HookLog.error("移除同步撤回字段失败，已放行原数据", exception);
        }
    }

    private static boolean isTargetMethod(Method method) {
        if (!"onMsfPush".equals(method.getName())
                || method.isSynthetic()
                || Modifier.isAbstract(method.getModifiers())
                || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return (parameters.length == 2 || parameters.length == 3)
                && parameters[0] == String.class
                && parameters[1] == byte[].class;
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.toString(method.getParameterTypes())
                + " -> " + method.getReturnType().getTypeName();
    }
}
