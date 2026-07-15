package com.huanhren.qqantirevoke.hook;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class QQAntiRevokeHook {
    private static final String[] MANAGER_CLASSES = {
            "com.tencent.imcore.message.BaseMessageManager",
            "com.tencent.imcore.message.C2CMessageManager",
            "com.tencent.imcore.message.BaseMessageManagerForTroopAndDisc"
    };

    private final ClassLoader classLoader;
    private final PreferenceReader preferenceReader;
    private int revokeEntryCount;
    private int removeHookCount;

    QQAntiRevokeHook(ClassLoader classLoader, PreferenceReader preferenceReader) {
        this.classLoader = classLoader;
        this.preferenceReader = preferenceReader;
    }

    boolean install() {
        Set<Class<?>> located = new LinkedHashSet<>();
        for (String className : MANAGER_CLASSES) {
            try {
                Class<?> type = XposedHelpers.findClassIfExists(className, classLoader);
                if (type != null) {
                    located.add(type);
                    HookLog.info("找到类 " + className);
                } else {
                    HookLog.debug("未找到类 " + className);
                }
            } catch (Throwable throwable) {
                HookLog.error("查找类失败 " + className, throwable);
            }
        }

        Class<?> base = located.stream()
                .filter(type -> type.getName().endsWith("BaseMessageManager"))
                .findFirst()
                .orElse(null);
        if (base == null) {
            HookLog.info("未找到 BaseMessageManager；为保留撤回小灰条，不启用完全拦截备用方案");
            return false;
        }

        hookRevokeEntries(base);
        for (Class<?> manager : located) {
            hookSingleRemoves(manager);
            hookBatchRemoves(manager);
        }

        HookLog.info("安装完成：撤回入口=" + revokeEntryCount + "，移除方法=" + removeHookCount);
        return revokeEntryCount > 0 && removeHookCount > 0;
    }

    private void hookRevokeEntries(Class<?> type) {
        List<Method> methods = MethodSelectors.revokeEntries(type);
        for (Method method : methods) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            PreferenceReader.Settings settings = preferenceReader.read();
                            HookLog.setDiagnostics(settings.diagnostics());
                            if (!settings.enabled()) return;
                            Object argument = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            RevokeContext.enter(argument);
                            HookLog.debug("进入撤回链路，标识数=" + RevokeContext.currentIdentifiers().size());
                        } catch (Throwable throwable) {
                            HookLog.error("进入撤回上下文失败", throwable);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (RevokeContext.isInsideRevoke()) {
                                RevokeContext.exit();
                                HookLog.debug("离开撤回链路");
                            }
                        } catch (Throwable throwable) {
                            HookLog.error("退出撤回上下文失败", throwable);
                        }
                    }
                });
                revokeEntryCount++;
                HookLog.info("Hook 撤回入口 " + MethodSelectors.signature(method));
            } catch (Throwable throwable) {
                HookLog.error("Hook 撤回入口失败 " + MethodSelectors.signature(method), throwable);
            }
        }
    }

    private void hookSingleRemoves(Class<?> type) {
        for (Method method : MethodSelectors.singleRemoveMethods(type)) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!RevokeContext.isInsideRevoke()) return;
                            PreferenceReader.Settings settings = preferenceReader.read();
                            HookLog.setDiagnostics(settings.diagnostics());
                            if (!settings.enabled()) return;

                            Object message = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            RevokeContext.MatchResult match = RevokeContext.matchesMessage(message);
                            if (match.matched()) {
                                HookLog.info("阻止单条消息移除：精确标识匹配 " + match.intersection());
                                param.setResult(MethodSelectors.defaultReturnValue(method.getReturnType()));
                            } else if (settings.aggressive()) {
                                HookLog.info("阻止单条消息移除：兼容模式（撤回线程内）");
                                param.setResult(MethodSelectors.defaultReturnValue(method.getReturnType()));
                            } else {
                                HookLog.debug("放行单条移除：标识未匹配，严格模式开启");
                            }
                        } catch (Throwable throwable) {
                            HookLog.error("处理单条移除 Hook 失败，已放行原调用", throwable);
                        }
                    }
                });
                removeHookCount++;
                HookLog.info("Hook 单条移除 " + MethodSelectors.signature(method));
            } catch (Throwable throwable) {
                HookLog.error("Hook 单条移除失败 " + MethodSelectors.signature(method), throwable);
            }
        }
    }

    private void hookBatchRemoves(Class<?> type) {
        for (Method method : MethodSelectors.batchRemoveMethods(type)) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!RevokeContext.isInsideRevoke()) return;
                            PreferenceReader.Settings settings = preferenceReader.read();
                            HookLog.setDiagnostics(settings.diagnostics());
                            if (!settings.enabled()) return;

                            Object messages = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            RevokeContext.BatchMatchResult match = RevokeContext.matchesBatch(messages);
                            if (match.allMatched()) {
                                HookLog.info("阻止批量消息移除：" + match.matched() + "/" + match.inspected() + " 精确匹配");
                                param.setResult(MethodSelectors.defaultReturnValue(method.getReturnType()));
                            } else if (settings.aggressive()) {
                                HookLog.info("阻止批量消息移除：兼容模式（撤回线程内，匹配 "
                                        + match.matched() + "/" + match.inspected() + "）");
                                param.setResult(MethodSelectors.defaultReturnValue(method.getReturnType()));
                            } else {
                                HookLog.debug("放行批量移除：并非全部消息匹配，严格模式开启");
                            }
                        } catch (Throwable throwable) {
                            HookLog.error("处理批量移除 Hook 失败，已放行原调用", throwable);
                        }
                    }
                });
                removeHookCount++;
                HookLog.info("Hook 批量移除 " + MethodSelectors.signature(method));
            } catch (Throwable throwable) {
                HookLog.error("Hook 批量移除失败 " + MethodSelectors.signature(method), throwable);
            }
        }
    }
}
