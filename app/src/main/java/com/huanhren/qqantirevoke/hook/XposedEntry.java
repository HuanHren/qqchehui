package com.huanhren.qqantirevoke.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedEntry implements IXposedHookLoadPackage {
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!QQ_PACKAGE.equals(lpparam.packageName)) return;

        HookLog.info("模块加载，进程=" + lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!INSTALLED.compareAndSet(false, true)) return;
                            try {
                                Context context = (Context) param.args[0];
                                HookLog.initialize(context);
                                HookLog.info("模块专属日志通道已连接，进程=" + lpparam.processName);
                                logHostVersion(context, lpparam.processName);
                                ClassLoader loader = context.getClassLoader() != null
                                        ? context.getClassLoader()
                                        : lpparam.classLoader;
                                PreferenceReader prefs = new PreferenceReader();
                                PreferenceReader.Settings settings = prefs.read();
                                HookLog.setDiagnostics(settings.diagnostics());
                                if (!settings.enabled()) {
                                    HookLog.info("模块设置为关闭，不安装 QQ Hook");
                                    return;
                                }
                                new QQAntiRevokeHook(loader, prefs).install();
                            } catch (Throwable throwable) {
                                HookLog.error("Application.attach 后安装 Hook 失败", throwable);
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            HookLog.error("Hook Application.attach 失败", throwable);
            INSTALLED.set(false);
        }
    }

    private static void logHostVersion(Context context, String processName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(QQ_PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            HookLog.info("宿主 QQ=" + info.versionName + "(" + versionCode + ")，进程=" + processName);
        } catch (Throwable throwable) {
            HookLog.error("读取 QQ 版本失败", throwable);
        }
    }
}
