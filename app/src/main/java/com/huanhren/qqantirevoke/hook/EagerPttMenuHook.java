package com.huanhren.qqantirevoke.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Installs the PTT menu hook immediately when QQ starts.
 *
 * <p>The previous implementation waited until a new PTT component was constructed. After a QQ
 * restart, already restored voice-message views could therefore miss the module menu until a new
 * voice arrived. Hooking the concrete component class up front makes the menu available for both
 * restored and newly received voice messages.</p>
 */
final class EagerPttMenuHook {
    private static final String BASE_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent";
    private static final String PTT_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent";
    private static final String AIO_MSG_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";

    private final ClassLoader classLoader;
    private final ActivePttMenuHook delegate;

    EagerPttMenuHook(android.content.Context context, ClassLoader classLoader,
            PreferenceReader preferences) {
        this.classLoader = classLoader;
        this.delegate = new ActivePttMenuHook(context, classLoader, preferences);
    }

    int install() {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(BASE_COMPONENT, classLoader);
            Class<?> pttClass = XposedHelpers.findClassIfExists(PTT_COMPONENT, classLoader);
            Class<?> msgClass = XposedHelpers.findClassIfExists(AIO_MSG_ITEM, classLoader);
            if (baseClass == null || pttClass == null || msgClass == null) {
                return 0;
            }

            Method getMsg = null;
            Method baseMenu = null;
            for (Method method : baseClass.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                if (msgClass.isAssignableFrom(method.getReturnType())) {
                    getMsg = method;
                } else if (List.class.isAssignableFrom(method.getReturnType())
                        && Modifier.isAbstract(method.getModifiers())) {
                    baseMenu = method;
                }
            }
            if (getMsg == null || baseMenu == null) {
                return 0;
            }
            getMsg.setAccessible(true);

            Method concreteMenu = findNoArgListMethod(pttClass, baseMenu.getName());
            if (concreteMenu == null) {
                return 0;
            }
            concreteMenu.setAccessible(true);

            Method injector = ActivePttMenuHook.class.getDeclaredMethod(
                    "injectForwardItem",
                    XC_MethodHook.MethodHookParam.class,
                    Method.class
            );
            injector.setAccessible(true);
            Method finalGetMsg = getMsg;
            XposedBridge.hookMethod(concreteMenu, new XC_MethodHook(80) {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        injector.invoke(delegate, param, finalGetMsg);
                    } catch (Throwable throwable) {
                        HookLog.error("主动添加语音转发菜单失败", unwrap(throwable));
                    }
                }
            });
            return 1;
        } catch (Throwable throwable) {
            HookLog.error("安装启动期语音菜单 Hook 失败", throwable);
            return 0;
        }
    }

    private static Method findNoArgListMethod(Class<?> type, String preferredName) {
        Method fallback = null;
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || !List.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                if (preferredName.equals(method.getName())) {
                    return method;
                }
                if (fallback == null) {
                    fallback = method;
                }
            }
        }
        return fallback;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable.getCause();
        return cause == null ? throwable : cause;
    }
}
