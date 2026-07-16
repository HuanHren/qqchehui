package com.huanhren.qqantirevoke.hook;

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ModulePrefs.QQ_PACKAGE.equals(lpparam.packageName)
                || (!MAIN_PROCESS.equals(lpparam.processName)
                && !MSF_PROCESS.equals(lpparam.processName))) {
            return;
        }

        XposedBridge.log("[QQAntiRevoke] v3.2 入口加载，等待 Application.attach，进程="
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
            HookLog.info("v3.2 独立文件日志已初始化");
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
                settingsHooks = new QqSettingsEntryHook(context, loader).install();
            }
            if (mainProcess && settings.pttForward()) {
                voiceHooks = new PttVoiceForwardHook(context, loader, preferences).install();
            }

            HookLog.info("v3.2 安装完成：NT onMsfPush=" + ntPushHooks
                    + "，旧链路备用入口=" + legacyHooks
                    + "，本地灰条=" + settings.showGrayTip()
                    + "，QQ设置入口=" + settingsHooks
                    + "，语音转发基础Hook=" + voiceHooks);
            if (ntPushHooks == 0) {
                HookLog.info("警告：未安装 NT onMsfPush Hook；当前版本可能无法拦截 NT 撤回");
            }
            if (mainProcess && settings.startupToast()) {
                showLoadedToast(context);
            }
        } catch (Throwable throwable) {
            HookLog.error("Application.attach 后安装 v3.2 Hook 失败", throwable);
        }
    }

    private static void showLoadedToast(Context context) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Toast.makeText(
                        context.getApplicationContext(),
                        "QQ 防撤回 NT v3.2 已加载",
                        Toast.LENGTH_SHORT
                ).show();
                HookLog.info("已显示模块加载成功提示");
            } catch (Throwable throwable) {
                HookLog.error("显示模块加载提示失败", throwable);
            }
        }, 1300L);
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
