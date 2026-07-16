package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
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

        XposedBridge.log("[QQAntiRevoke] v3.3 入口加载，等待 Application.attach，进程="
                + lpparam.processName);
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
            XposedBridge.log(throwable);
            INSTALLED.set(false);
        }
    }

    private static void installAfterAttach(Context context,
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            boolean mainProcess = MAIN_PROCESS.equals(lpparam.processName);
            HookLog.initialize(context, lpparam.processName);
            HookLog.info("v3.3 日志通道已初始化：Provider + 显式广播 + QQ 私有文件");
            logHostVersion(context, lpparam.processName);

            ClassLoader loader = context.getClassLoader() != null
                    ? context.getClassLoader()
                    : lpparam.classLoader;
            PreferenceReader preferences = new PreferenceReader();
            PreferenceReader.Settings settings = preferences.read();
            HookLog.setDiagnostics(settings.diagnostics());
            if (!settings.enabled()) {
                HookLog.info("模块总开关关闭，不安装功能 Hook");
                return;
            }

            int ntPushHooks = new NtRecallPushHook(loader, preferences).install();
            int legacyHooks = new LegacyRecallFallbackHook(loader, preferences).install();
            int settingsHooks = 0;
            int voiceHooks = 0;

            if (mainProcess && settings.qqSettingsEntry()) {
                settingsHooks += new QqSettingsProviderEntryHook(
                        context,
                        loader,
                        preferences
                ).install();
                settingsHooks += new QqSettingsEntryHook(context, loader).install();
            }
            if (mainProcess && settings.pttForward()) {
                voiceHooks += new PttVoiceForwardHook(context, loader, preferences).install();
                voiceHooks += new ActivePttMenuHook(context, loader, preferences).install();
            }
            if (mainProcess && settings.startupToast()) {
                installForegroundLoadedNotice();
            }

            HookLog.info("v3.3 安装完成：NT onMsfPush=" + ntPushHooks
                    + "，旧链路备用入口=" + legacyHooks
                    + "，本地灰条=" + settings.showGrayTip()
                    + "，QQ设置入口Hook=" + settingsHooks
                    + "，语音转发Hook=" + voiceHooks);
            if (ntPushHooks == 0) {
                HookLog.info("警告：未安装 NT onMsfPush Hook；当前版本可能无法拦截 NT 撤回");
            }
        } catch (Throwable throwable) {
            HookLog.error("Application.attach 后安装 v3.3 Hook 失败", throwable);
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
                                    "QQ 防撤回 NT v3.3 已加载",
                                    Toast.LENGTH_SHORT
                            ).show();
                            HookLog.info("已在 QQ 前台 Activity 显示模块加载提示");
                        } catch (Throwable throwable) {
                            HookLog.error("显示 QQ 前台加载提示失败", throwable);
                        }
                    }, 350L);
                }
            });
            HookLog.info("已安装 QQ 前台 Activity 加载提示 Hook");
        } catch (Throwable throwable) {
            HookLog.error("安装 QQ 前台加载提示 Hook 失败", throwable);
        }
    }

    private static void logHostVersion(Context context, String processName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(ModulePrefs.QQ_PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            HookLog.info("宿主 QQ=" + info.versionName + "(" + versionCode + ")，进程="
                    + processName + "，Android=" + Build.VERSION.RELEASE + "/API "
                    + Build.VERSION.SDK_INT);
        } catch (Throwable throwable) {
            HookLog.error("读取 QQ 版本失败", throwable);
        }
    }
}
