package com.huanhren.qqantirevoke.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ModulePrefs.QQ_PACKAGE.equals(lpparam.packageName)
                || (!MAIN_PROCESS.equals(lpparam.processName)
                && !MSF_PROCESS.equals(lpparam.processName))) {
            return;
        }

        XposedBridge.log("[QQAntiRevoke] v3.2.1 入口加载，等待 Application.attach，进程="
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
            HostLogBootstrap.writeProbe(context, lpparam.processName);
            HookLog.initialize(context, lpparam.processName);
            HookLog.info("v3.2.1 独立文件日志已初始化，filesDir="
                    + context.getFilesDir().getAbsolutePath());
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
            int oldSettingsHooks = 0;
            int oldVoiceHooks = 0;
            int foregroundUiHooks = 0;
            int directVoiceMenuHooks = 0;

            if (mainProcess && settings.qqSettingsEntry()) {
                oldSettingsHooks = new QqSettingsEntryHook(context, loader).install();
            }
            if (mainProcess && settings.pttForward()) {
                oldVoiceHooks = new PttVoiceForwardHook(context, loader, preferences).install();
                directVoiceMenuHooks = new PttVoiceMenuInjectionHook(
                        context,
                        loader,
                        preferences
                ).install();
            }
            if (mainProcess) {
                foregroundUiHooks = new QqForegroundUiHook(
                        settings.qqSettingsEntry(),
                        settings.startupToast()
                ).install();
            }

            HookLog.info("v3.2.1 安装完成：NT onMsfPush=" + ntPushHooks
                    + "，旧链路备用入口=" + legacyHooks
                    + "，本地灰条=" + settings.showGrayTip()
                    + "，旧QQ设置入口=" + oldSettingsHooks
                    + "，前台设置入口=" + foregroundUiHooks
                    + "，旧语音Hook=" + oldVoiceHooks
                    + "，直接新增语音菜单=" + directVoiceMenuHooks);
            if (ntPushHooks == 0) {
                HookLog.info("警告：未安装 NT onMsfPush Hook；当前版本可能无法拦截 NT 撤回");
            }
        } catch (Throwable throwable) {
            HookLog.error("Application.attach 后安装 v3.2.1 Hook 失败", throwable);
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
