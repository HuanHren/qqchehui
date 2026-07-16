package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.huanhren.qqantirevoke.ModulePrefs;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedEntry implements IXposedHookLoadPackage {
    private static final String MAIN_PROCESS = ModulePrefs.QQ_PACKAGE;
    private static final String MSF_PROCESS = ModulePrefs.QQ_PACKAGE + ":MSF";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FOREGROUND_NOTICE_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FOREGROUND_NOTICE_SHOWN = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ModulePrefs.QQ_PACKAGE.equals(lpparam.packageName)
                || (!MAIN_PROCESS.equals(lpparam.processName)
                && !MSF_PROCESS.equals(lpparam.processName))) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!INSTALLED.compareAndSet(false, true)) {
                                return;
                            }
                            installAfterAttach((Context) param.args[0], lpparam);
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log("[QQAntiRevoke] Hook Application.attach 失败: " + throwable);
            INSTALLED.set(false);
        }
    }

    private static void installAfterAttach(Context context,
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            boolean mainProcess = MAIN_PROCESS.equals(lpparam.processName);
            ClassLoader loader = context.getClassLoader() != null
                    ? context.getClassLoader()
                    : lpparam.classLoader;
            PreferenceReader preferences = new PreferenceReader();
            PreferenceReader.Settings settings = preferences.read();
            if (!settings.enabled()) {
                return;
            }

            new NtRecallPushHook(loader, preferences).install();
            new LegacyRecallFallbackHook(loader, preferences).install();

            if (mainProcess && settings.qqSettingsEntry()) {
                new QqNativeSettingsEntryHook(context, loader, preferences).install();
            }
            if (mainProcess && settings.pttForward()) {
                // Priority 100 modern confirmation is installed before the older compatibility hook.
                new ModernVoiceConfirmHook(context, loader, preferences).install();
                new PttVoiceForwardHook(context, loader, preferences).install();
                // Hook the concrete PTT class immediately so restored messages work after restart.
                new EagerPttMenuHook(context, loader, preferences).install();
            }
            if (mainProcess && settings.startupToast()) {
                installForegroundLoadedNotice();
            }
        } catch (Throwable throwable) {
            HookLog.error("Application.attach 后安装 v3.4 Hook 失败", throwable);
        }
    }

    private static void installForegroundLoadedNotice() {
        if (!FOREGROUND_NOTICE_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (FOREGROUND_NOTICE_SHOWN.get() || !(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity activity = (Activity) param.thisObject;
                    if (!ModulePrefs.QQ_PACKAGE.equals(activity.getPackageName())
                            || !FOREGROUND_NOTICE_SHOWN.compareAndSet(false, true)) {
                        return;
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Toast.makeText(
                                    activity,
                                    "QQ 防撤回 NT v3.4 已加载",
                                    Toast.LENGTH_SHORT
                            ).show();
                        } catch (Throwable throwable) {
                            HookLog.error("显示 QQ 前台加载提示失败", throwable);
                        }
                    }, 350L);
                }
            });
        } catch (Throwable throwable) {
            HookLog.error("安装 QQ 前台加载提示失败", throwable);
        }
    }
}
