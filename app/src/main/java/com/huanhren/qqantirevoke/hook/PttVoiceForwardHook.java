package com.huanhren.qqantirevoke.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.DexFile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ 9.2.10 NT voice forwarding.
 *
 * <p>The native QQ forward dispatcher treats PTT messages as plain text and drops the
 * PttElement. This hook combines two interception points:</p>
 * <ol>
 *     <li>Patch the existing "转发" menu click for AIOPttContentComponent.</li>
 *     <li>Find and short-circuit NtMsgForwardUtils as a secondary native-entry guard.</li>
 * </ol>
 * The QQ recipient picker is retained. After target selection the module rebuilds a fresh
 * PttElement through IMsgUtilApi and sends it through IMsgService.</p>
 */
final class PttVoiceForwardHook {
    private static final String BASE_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent";
    private static final String PTT_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent";
    private static final String AIO_MSG_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";
    private static final String FORWARD_ACTIVITY =
            "com.tencent.mobileqq.activity.ForwardRecentActivity";
    private static final String FORWARD_BASE_OPTION =
            "com.tencent.mobileqq.forward.ForwardBaseOption";
    private static final String DIRECT_FORWARD_ACTIVITY =
            "com.tencent.mobileqq.activity.DirectForwardActivity";

    private static final String EXTRA_PATH = "ptt_forward_path";
    private static final String EXTRA_DURATION = "qqantirevoke_ptt_duration";

    private static final ExecutorService FINDER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QQAntiRevoke-PttClassFinder");
        thread.setDaemon(true);
        return thread;
    });

    private final Context applicationContext;
    private final ClassLoader classLoader;
    private final PreferenceReader preferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Class<?>> hookedComponentClasses =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Method> hookedMenuClickMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Method> hookedNativeForwardMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Object, VoiceAction> voiceMenuActions =
            Collections.synchronizedMap(new WeakHashMap<>());

    PttVoiceForwardHook(Context context, ClassLoader classLoader, PreferenceReader preferences) {
        this.applicationContext = context.getApplicationContext();
        this.classLoader = classLoader;
        this.preferences = preferences;
    }

    int install() {
        int installed = 0;
        installed += installForwardConfirmationHook();
        installed += installPttMenuOverride();
        FINDER.execute(this::findAndHookNativeForwardUtility);
        return installed;
    }

    private int installPttMenuOverride() {
        Class<?> base = XposedHelpers.findClassIfExists(BASE_COMPONENT, classLoader);
        Class<?> msgClass = XposedHelpers.findClassIfExists(AIO_MSG_ITEM, classLoader);
        if (base == null || msgClass == null) {
            HookLog.info("语音转发菜单备用 Hook 未安装：未找到 BaseContentComponent/AIOMsgItem");
            return 0;
        }

        Method getMsg = null;
        Method getMenu = null;
        for (Method method : base.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            if (msgClass.isAssignableFrom(method.getReturnType())) {
                getMsg = method;
            } else if (List.class.isAssignableFrom(method.getReturnType())
                    && Modifier.isAbstract(method.getModifiers())) {
                getMenu = method;
            }
        }
        if (getMsg == null || getMenu == null) {
            HookLog.info("语音转发菜单备用 Hook 未安装：未识别消息/菜单方法");
            return 0;
        }
        getMsg.setAccessible(true);
        Method finalGetMsg = getMsg;
        String menuMethodName = getMenu.getName();

        XposedBridge.hookAllConstructors(base, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object component = param.thisObject;
                if (component == null || !PTT_COMPONENT.equals(component.getClass().getName())) {
                    return;
                }
                Class<?> componentClass = component.getClass();
                if (!hookedComponentClasses.add(componentClass)) {
                    return;
                }
                try {
                    Method method = componentClass.getMethod(menuMethodName);
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam menuParam) {
                            attachToExistingForwardItem(menuParam, finalGetMsg);
                        }
                    });
                    HookLog.info("已安装语音消息原生转发菜单修复：" + componentClass.getName()
                            + "#" + menuMethodName);
                } catch (Throwable throwable) {
                    HookLog.error("安装语音消息菜单修复失败", throwable);
                }
            }
        });
        return 1;
    }

    @SuppressWarnings("unchecked")
    private void attachToExistingForwardItem(XC_MethodHook.MethodHookParam param, Method getMsg) {
        try {
            if (!preferences.read().pttForward()) {
                return;
            }
            Object result = param.getResult();
            if (!(result instanceof List<?>)) {
                return;
            }
            Object msgItem = getMsg.invoke(param.thisObject);
            VoicePayload payload = extractVoicePayload(msgItem);
            if (payload == null) {
                return;
            }
            Activity activity = findActivity(param.thisObject);
            List<Object> items = (List<Object>) result;
            for (Object item : items) {
                if (item == null || !isForwardMenuItem(item)) {
                    continue;
                }
                voiceMenuActions.put(item, new VoiceAction(activity, payload));
                hookMenuItemClickMethods(item.getClass());
                HookLog.debug("已接管语音消息现有“转发”菜单，path=" + payload.filePath);
                return;
            }
            HookLog.debug("语音消息菜单中未找到“转发”项，将依赖 NtMsgForwardUtils 入口备用 Hook");
        } catch (Throwable throwable) {
            HookLog.error("处理语音消息转发菜单失败", throwable);
        }
    }

    private boolean isForwardMenuItem(Object item) {
        for (Class<?> cursor = item.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || !CharSequence.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object title = method.invoke(item);
                    if (title != null && title.toString().contains("转发")) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Continue testing other title getters.
                }
            }
        }
        return false;
    }

    private void hookMenuItemClickMethods(Class<?> itemClass) {
        for (Class<?> cursor = itemClass; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0
                        || method.getReturnType() != void.class
                        || !hookedMenuClickMethods.add(method)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            VoiceAction action = voiceMenuActions.remove(param.thisObject);
                            if (action == null) {
                                return;
                            }
                            param.setResult(null);
                            Activity activity = action.activity == null
                                    ? currentActivity()
                                    : action.activity.get();
                            launchForwardPicker(activity, action.payload);
                        }
                    });
                } catch (Throwable throwable) {
                    hookedMenuClickMethods.remove(method);
                    HookLog.error("Hook 语音转发菜单点击方法失败：" + method, throwable);
                }
            }
        }
    }

    private void findAndHookNativeForwardUtility() {
        try {
            Set<String> candidates = new HashSet<>();
            Collections.addAll(candidates,
                    "com.tencent.mobileqq.forward.NtMsgForwardUtils",
                    "com.tencent.mobileqq.aio.forward.NtMsgForwardUtils",
                    "com.tencent.mobileqq.aio.msglist.forward.NtMsgForwardUtils",
                    "com.tencent.mobileqq.activity.aio.forward.NtMsgForwardUtils");
            candidates.addAll(findClassNamesEndingWith(".NtMsgForwardUtils"));

            int count = 0;
            for (String name : candidates) {
                Class<?> utility = XposedHelpers.findClassIfExists(name, classLoader);
                if (utility == null) {
                    continue;
                }
                for (Method method : utility.getDeclaredMethods()) {
                    if (!isNativeForwardEntry(method) || !hookedNativeForwardMethods.add(method)) {
                        continue;
                    }
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            interceptNativeForwardEntry(method, param);
                        }
                    });
                    count++;
                    HookLog.info("已 Hook QQ 原生语音转发入口：" + method);
                }
            }
            if (count == 0) {
                HookLog.info("未定位 NtMsgForwardUtils 原生入口，语音转发继续使用菜单接管方案");
            }
        } catch (Throwable throwable) {
            HookLog.error("扫描 NtMsgForwardUtils 失败，保留菜单接管方案", throwable);
        }
    }

    private boolean isNativeForwardEntry(Method method) {
        if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 3) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return (Activity.class.isAssignableFrom(parameters[0])
                || Context.class.isAssignableFrom(parameters[0]))
                && (parameters[2].getName().contains("AIOMsgItem")
                || parameters[2].getName().contains("PttMsgItem")
                || parameters[2] == Object.class);
    }

    private void interceptNativeForwardEntry(Method method, XC_MethodHook.MethodHookParam param) {
        try {
            if (!preferences.read().pttForward() || param.args == null || param.args.length < 3) {
                return;
            }
            VoicePayload payload = extractVoicePayload(param.args[2]);
            if (payload == null) {
                return;
            }
            Context context = param.args[0] instanceof Context
                    ? (Context) param.args[0]
                    : applicationContext;
            param.setResult(defaultValue(method.getReturnType()));
            HookLog.info("已从 NtMsgForwardUtils 短路错误的纯文本转发分支，path="
                    + payload.filePath);
            launchForwardPicker(unwrapActivity(context), payload);
        } catch (Throwable throwable) {
            HookLog.error("拦截 QQ 原生语音转发入口失败，已放行", throwable);
        }
    }

    private Set<String> findClassNamesEndingWith(String suffix) {
        Set<String> result = new HashSet<>();
        try {
            Field pathListField = findField(classLoader.getClass(), "pathList");
            Object pathList = pathListField.get(classLoader);
            Field elementsField = findField(pathList.getClass(), "dexElements");
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) {
                return result;
            }
            for (Object element : elements) {
                if (element == null) {
                    continue;
                }
                Field dexFileField;
                try {
                    dexFileField = findField(element.getClass(), "dexFile");
                } catch (NoSuchFieldException ignored) {
                    continue;
                }
                Object value = dexFileField.get(element);
                if (!(value instanceof DexFile)) {
                    continue;
                }
                Enumeration<String> entries = ((DexFile) value).entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (name.endsWith(suffix)) {
                        result.add(name);
                    }
                }
            }
        } catch (Throwable throwable) {
            HookLog.debug("运行时 DEX 枚举不可用：" + throwable);
        }
        return result;
    }

    private int installForwardConfirmationHook() {
        try {
            Class<?> baseOption = XposedHelpers.findClassIfExists(FORWARD_BASE_OPTION, classLoader);
            if (baseOption == null) {
                Class<?> directActivity = XposedHelpers.findClassIfExists(DIRECT_FORWARD_ACTIVITY, classLoader);
                if (directActivity != null) {
                    for (Field field : directActivity.getDeclaredFields()) {
                        Class<?> type = field.getType();
                        if (!Modifier.isStatic(field.getModifiers())
                                && Modifier.isAbstract(type.getModifiers())
                                && !type.getName().startsWith("android.")) {
                            baseOption = type;
                            break;
                        }
                    }
                }
            }
            if (baseOption == null) {
                HookLog.info("语音转发确认 Hook 未安装：未找到 ForwardBaseOption");
                return 0;
            }

            Method confirm = null;
            try {
                confirm = baseOption.getDeclaredMethod("buildConfirmDialog");
            } catch (NoSuchMethodException ignored) {
                for (Method method : baseOption.getDeclaredMethods()) {
                    if (method.getParameterCount() == 0
                            && method.getReturnType() == void.class
                            && Modifier.isFinal(method.getModifiers())) {
                        confirm = method;
                        break;
                    }
                }
            }
            if (confirm == null) {
                HookLog.info("语音转发确认 Hook 未安装：未识别确认方法");
                return 0;
            }
            confirm.setAccessible(true);
            XposedBridge.hookMethod(confirm, new XC_MethodHook(51) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    handleForwardConfirmation(param);
                }
            });
            HookLog.info("已安装语音转发联系人确认 Hook：" + confirm);
            return 1;
        } catch (Throwable throwable) {
            HookLog.error("安装语音转发确认 Hook 失败", throwable);
            return 0;
        }
    }

    private void handleForwardConfirmation(XC_MethodHook.MethodHookParam param) {
        try {
            Bundle data = findBundle(param.thisObject);
            if (data == null || !data.containsKey(EXTRA_PATH)) {
                return;
            }
            param.setResult(null);
            String path = data.getString(EXTRA_PATH);
            int duration = data.getInt(EXTRA_DURATION, 0);
            File file = path == null ? null : new File(path);
            Activity activity = findActivity(param.thisObject);
            if (activity == null) {
                activity = currentActivity();
            }
            if (activity == null || file == null || !file.isFile()) {
                showToast(activity, "语音文件不存在，请先播放一次语音后重试");
                HookLog.info("语音转发失败：确认页找不到 Activity 或文件，path=" + path);
                return;
            }

            List<ForwardTarget> targets = parseForwardTargets(data);
            if (targets.isEmpty()) {
                showToast(activity, "没有解析到转发目标");
                HookLog.info("语音转发失败：未解析到目标，bundleKeys=" + data.keySet());
                return;
            }

            Activity finalActivity = activity;
            int finalDuration = duration;
            new AlertDialog.Builder(activity)
                    .setTitle("转发语音")
                    .setMessage("发送给 " + describeTargets(targets) + "？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("发送", (dialog, which) -> {
                        int success = 0;
                        for (ForwardTarget target : targets) {
                            if (sendVoice(file, finalDuration, target)) {
                                success++;
                            }
                        }
                        if (success == targets.size()) {
                            showToast(finalActivity, "语音已发送");
                            finalActivity.finish();
                        } else {
                            showToast(finalActivity, "语音发送完成 " + success + "/" + targets.size());
                        }
                    })
                    .show();
        } catch (Throwable throwable) {
            HookLog.error("处理语音转发确认页失败", throwable);
        }
    }

    private void launchForwardPicker(Activity activity, VoicePayload payload) {
        Runnable action = () -> {
            Activity targetActivity = activity != null ? activity : currentActivity();
            Context context = targetActivity != null ? targetActivity : applicationContext;
            File file = new File(payload.filePath);
            if (!file.isFile()) {
                showToast(targetActivity, "语音尚未下载，请先播放一次再转发");
                HookLog.info("语音转发未启动：本地文件不存在，path=" + payload.filePath);
                return;
            }
            try {
                Class<?> activityClass = Class.forName(FORWARD_ACTIVITY, false, classLoader);
                Intent intent = new Intent(context, activityClass);
                intent.putExtra("selection_mode", 0);
                intent.putExtra("direct_send_if_dataline_forward", false);
                intent.putExtra("forward_text", "null");
                intent.putExtra(EXTRA_PATH, file.getAbsolutePath());
                intent.putExtra(EXTRA_DURATION, payload.durationMs);
                intent.putExtra("forward_type", -1);
                intent.putExtra("caller_name", "ChatActivity");
                intent.putExtra("k_smartdevice", false);
                intent.putExtra("k_dataline", false);
                intent.putExtra("k_forward_title", "语音转发");
                if (!(context instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
                HookLog.info("已打开 QQ 联系人选择器，语音 path=" + file.getAbsolutePath());
            } catch (Throwable throwable) {
                HookLog.error("打开 QQ 语音转发联系人选择器失败", throwable);
                showToast(targetActivity, "无法打开 QQ 转发页面");
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private boolean sendVoice(File file, int hintedDurationMs, ForwardTarget target) {
        try {
            Object msgUtilApi = qRouteApi("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgService = qRouteApi("com.tencent.qqnt.msg.api.IMsgService");
            if (msgUtilApi == null || msgService == null) {
                throw new IllegalStateException("IMsgUtilApi/IMsgService unavailable");
            }

            Method sendMethod = null;
            for (Method method : msgService.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("sendMsg".equals(method.getName())
                        && parameters.length == 3
                        && parameters[0].getSimpleName().equals("Contact")
                        && List.class.isAssignableFrom(parameters[1])) {
                    sendMethod = method;
                    break;
                }
            }
            if (sendMethod == null) {
                throw new NoSuchMethodException("IMsgService.sendMsg(Contact,List,Callback)");
            }

            int chatType = normalizeChatType(target.type);
            String peer = normalizePeer(target.peer, chatType);
            Constructor<?> contactConstructor = sendMethod.getParameterTypes()[0]
                    .getDeclaredConstructor(int.class, String.class, String.class);
            contactConstructor.setAccessible(true);
            Object contact = contactConstructor.newInstance(chatType, peer, "");

            Method createPtt = null;
            for (Method method : msgUtilApi.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("createPttElement".equals(method.getName())
                        && parameters.length == 3
                        && parameters[0] == String.class
                        && (parameters[1] == int.class || parameters[1] == Integer.class)
                        && List.class.isAssignableFrom(parameters[2])) {
                    createPtt = method;
                    break;
                }
            }
            if (createPtt == null) {
                throw new NoSuchMethodException("IMsgUtilApi.createPttElement");
            }

            int durationMs = hintedDurationMs > 0 ? hintedDurationMs : 1000;
            ArrayList<Byte> waveform = defaultWaveform();
            Object pttElement = createPtt.invoke(msgUtilApi, file.getAbsolutePath(), durationMs, waveform);
            ArrayList<Object> elements = new ArrayList<>();
            elements.add(pttElement);
            Object callback = createCallback(sendMethod.getParameterTypes()[2]);
            sendMethod.invoke(msgService, contact, elements, callback);
            HookLog.info("已通过 NT API 发送语音：peer=" + peer + ", chatType=" + chatType
                    + ", durationMs=" + durationMs + ", path=" + file.getAbsolutePath());
            return true;
        } catch (Throwable throwable) {
            HookLog.error("通过 NT API 发送语音失败：target=" + target, throwable);
            return false;
        }
    }

    private Object qRouteApi(String interfaceName) throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName(interfaceName, false, classLoader);
        Class<?> qRouteClass = Class.forName(
                "com.tencent.mobileqq.qroute.QRoute",
                false,
                classLoader
        );
        Method api = qRouteClass.getMethod("api", Class.class);
        return api.invoke(null, apiClass);
    }

    private String normalizePeer(String peer, int chatType) {
        if (peer == null) {
            return "";
        }
        if (chatType == 2 || peer.startsWith("u_") || !peer.chars().allMatch(Character::isDigit)) {
            return peer;
        }
        try {
            Object relation = qRouteApi("com.tencent.relation.common.api.IRelationNTUinAndUidApi");
            Method getUid = relation.getClass().getMethod("getUidFromUin", String.class);
            Object uid = getUid.invoke(relation, peer);
            if (uid instanceof String && !((String) uid).isEmpty()) {
                return (String) uid;
            }
        } catch (Throwable throwable) {
            HookLog.debug("UIN 转 UID 失败，将直接使用原值：" + throwable);
        }
        return peer;
    }

    private static int normalizeChatType(int type) {
        if (type == 1 || type == 2) {
            return type;
        }
        if (type == 0) {
            return 1;
        }
        return type + 1;
    }

    private VoicePayload extractVoicePayload(Object msgItem) {
        if (msgItem == null) {
            return null;
        }
        Object ptt = findPttElement(msgItem);
        if (ptt == null) {
            return null;
        }
        String path = readString(ptt, "getFilePath", "filePath", "path");
        if (path == null || path.isEmpty()) {
            return null;
        }
        long duration = readLong(ptt, "getDuration", "duration");
        int durationMs;
        if (duration <= 0) {
            durationMs = 1000;
        } else if (duration < 1000) {
            durationMs = (int) Math.min(Integer.MAX_VALUE, duration * 1000L);
        } else {
            durationMs = (int) Math.min(Integer.MAX_VALUE, duration);
        }
        return new VoicePayload(path, durationMs);
    }

    private Object findPttElement(Object msgItem) {
        for (Class<?> cursor = msgItem.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && "PttElement".equals(method.getReturnType().getSimpleName())) {
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(msgItem);
                        if (value != null) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // Continue searching.
                    }
                }
            }
            for (Field field : cursor.getDeclaredFields()) {
                if (!"PttElement".equals(field.getType().getSimpleName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(msgItem);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // Continue searching.
                }
            }
        }
        return null;
    }

    private static String readString(Object target, String methodName, String... fieldNames) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value != null) {
                return value.toString();
            }
        } catch (Throwable ignored) {
            // Fall through to fields.
        }
        for (String name : fieldNames) {
            try {
                Field field = findField(target.getClass(), name);
                Object value = field.get(target);
                if (value != null) {
                    return value.toString();
                }
            } catch (Throwable ignored) {
                // Continue.
            }
        }
        return null;
    }

    private static long readLong(Object target, String methodName, String... fieldNames) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
            // Fall through to fields.
        }
        for (String name : fieldNames) {
            try {
                Field field = findField(target.getClass(), name);
                Object value = field.get(target);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (Throwable ignored) {
                // Continue.
            }
        }
        return 0;
    }

    private Bundle findBundle(Object object) {
        for (Class<?> cursor = object.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (field.getType() != Bundle.class || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Bundle bundle = (Bundle) field.get(object);
                    if (bundle != null && bundle.containsKey(EXTRA_PATH)) {
                        return bundle;
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return null;
    }

    private List<ForwardTarget> parseForwardTargets(Bundle data) {
        ArrayList<ForwardTarget> result = new ArrayList<>();
        ArrayList<Parcelable> multiple = data.getParcelableArrayList("forward_multi_target");
        if (multiple != null) {
            for (Object object : multiple) {
                ForwardTarget target = parseTargetObject(object);
                if (target != null) {
                    result.add(target);
                }
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        String peer = firstString(data, "uin", "uid", "peerUid", "targetUin");
        int type = firstInt(data, -1, "uintype", "uinType", "chatType", "type");
        String name = firstString(data, "uinname", "nick", "name");
        if (peer != null && type >= 0) {
            result.add(new ForwardTarget(peer, type, name));
        }
        return result;
    }

    private ForwardTarget parseTargetObject(Object object) {
        if (object == null) {
            return null;
        }
        if (object instanceof Bundle) {
            Bundle bundle = (Bundle) object;
            String peer = firstString(bundle, "uin", "uid", "peerUid", "targetUin");
            int type = firstInt(bundle, -1, "uintype", "uinType", "chatType", "type");
            String name = firstString(bundle, "uinname", "nick", "name");
            return peer == null || type < 0 ? null : new ForwardTarget(peer, type, name);
        }

        String peer = null;
        String name = null;
        int type = -1;
        for (Class<?> cursor = object.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    String lower = field.getName().toLowerCase();
                    if (value instanceof String) {
                        if (peer == null && (lower.contains("uin") || lower.contains("uid")
                                || lower.contains("peer"))) {
                            peer = (String) value;
                        } else if (name == null && (lower.contains("name") || lower.contains("nick"))) {
                            name = (String) value;
                        }
                    } else if (value instanceof Number && (lower.contains("type") || lower.contains("chat"))) {
                        type = ((Number) value).intValue();
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(object);
                    String lower = method.getName().toLowerCase();
                    if (value instanceof String) {
                        if (peer == null && (lower.contains("uin") || lower.contains("uid")
                                || lower.contains("peer"))) {
                            peer = (String) value;
                        } else if (name == null && (lower.contains("name") || lower.contains("nick"))) {
                            name = (String) value;
                        }
                    } else if (value instanceof Number && (lower.contains("type") || lower.contains("chat"))) {
                        type = ((Number) value).intValue();
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return peer == null || type < 0 ? null : new ForwardTarget(peer, type, name);
    }

    private static String firstString(Bundle bundle, String... keys) {
        for (String key : keys) {
            Object value = bundle.get(key);
            if (value != null && !value.toString().isEmpty()) {
                return value.toString();
            }
        }
        return null;
    }

    private static int firstInt(Bundle bundle, int fallback, String... keys) {
        for (String key : keys) {
            Object value = bundle.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return fallback;
    }

    private Activity findActivity(Object object) {
        if (object instanceof Activity) {
            return (Activity) object;
        }
        if (object instanceof Context) {
            Activity activity = unwrapActivity((Context) object);
            if (activity != null) {
                return activity;
            }
        }
        for (Class<?> cursor = object.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value instanceof Activity) {
                        return (Activity) value;
                    }
                    if (value instanceof Context) {
                        Activity activity = unwrapActivity((Context) value);
                        if (activity != null) {
                            return activity;
                        }
                    }
                } catch (Throwable ignored) {
                    // Continue.
                }
            }
        }
        return null;
    }

    private static Activity unwrapActivity(Context context) {
        Context cursor = context;
        while (cursor instanceof ContextWrapper) {
            if (cursor instanceof Activity) {
                return (Activity) cursor;
            }
            Context base = ((ContextWrapper) cursor).getBaseContext();
            if (base == cursor) {
                break;
            }
            cursor = base;
        }
        return cursor instanceof Activity ? (Activity) cursor : null;
    }

    @SuppressWarnings("unchecked")
    private Activity currentActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object activityThread = current.invoke(null);
            Field activitiesField = findField(activityThreadClass, "mActivities");
            Map<Object, Object> activities = (Map<Object, Object>) activitiesField.get(activityThread);
            if (activities == null) {
                return null;
            }
            for (Object record : activities.values()) {
                Field pausedField = findField(record.getClass(), "paused");
                Object paused = pausedField.get(record);
                if (Boolean.TRUE.equals(paused)) {
                    continue;
                }
                Field activityField = findField(record.getClass(), "activity");
                Object activity = activityField.get(record);
                if (activity instanceof Activity) {
                    return (Activity) activity;
                }
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue.
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private static ArrayList<Byte> defaultWaveform() {
        ArrayList<Byte> values = new ArrayList<>(30);
        int[] raw = {24, 27, 31, 35, 39, 44, 48, 43, 37, 32,
                29, 34, 41, 47, 52, 49, 42, 36, 30, 26,
                28, 33, 38, 45, 50, 46, 40, 34, 29, 25};
        for (int value : raw) {
            values.add((byte) value);
        }
        return values;
    }

    private Object createCallback(Class<?> callbackType) {
        if (callbackType == null || !callbackType.isInterface()) {
            return null;
        }
        return java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{callbackType},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static String describeTargets(List<ForwardTarget> targets) {
        if (targets.size() == 1) {
            ForwardTarget target = targets.get(0);
            return target.name == null || target.name.isEmpty() ? target.peer : target.name;
        }
        return targets.size() + " 个会话";
    }

    private static void showToast(Activity activity, String message) {
        Context context = activity != null ? activity : null;
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class VoicePayload {
        final String filePath;
        final int durationMs;

        VoicePayload(String filePath, int durationMs) {
            this.filePath = filePath;
            this.durationMs = durationMs;
        }
    }

    private static final class VoiceAction {
        final WeakReference<Activity> activity;
        final VoicePayload payload;

        VoiceAction(Activity activity, VoicePayload payload) {
            this.activity = activity == null ? null : new WeakReference<>(activity);
            this.payload = payload;
        }
    }

    private static final class ForwardTarget {
        final String peer;
        final int type;
        final String name;

        ForwardTarget(String peer, int type, String name) {
            this.peer = peer;
            this.type = type;
            this.name = name;
        }

        @Override
        public String toString() {
            return "ForwardTarget{peer='" + peer + "', type=" + type + ", name='" + name + "'}";
        }
    }
}
