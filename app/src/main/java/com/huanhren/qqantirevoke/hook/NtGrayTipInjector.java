package com.huanhren.qqantirevoke.hook;

import com.huanhren.qqantirevoke.ModulePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NtGrayTipInjector {
    private static final int CHAT_TYPE_C2C = 1;
    private static final int CHAT_TYPE_GROUP = 2;
    private static final long BUSI_ID_C2C = 2021L;
    private static final long BUSI_ID_GROUP = 2022L;
    private static final long DUPLICATE_WINDOW_MS = 15_000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QQAntiRevoke-GrayTip");
        thread.setDaemon(true);
        return thread;
    });

    private final ClassLoader classLoader;
    private final Map<String, Long> recentEvents = new ConcurrentHashMap<>();

    NtGrayTipInjector(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    void enqueue(List<NtRecallParser.Event> events, String template) {
        if (events == null || events.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        recentEvents.entrySet().removeIf(entry -> now - entry.getValue() > DUPLICATE_WINDOW_MS);
        for (NtRecallParser.Event event : events) {
            if (event == null || event.kind() == NtRecallParser.Event.Kind.SYNC_UNKNOWN) {
                continue;
            }
            String key = event.stableKey();
            Long previous = recentEvents.put(key, now);
            if (previous != null && now - previous <= DUPLICATE_WINDOW_MS) {
                HookLog.debug("跳过重复灰条事件：" + key);
                continue;
            }
            String safeTemplate = normalizeTemplate(template);
            EXECUTOR.execute(() -> insert(event, safeTemplate));
        }
    }

    private void insert(NtRecallParser.Event event, String template) {
        try {
            Object appRuntime = getAppRuntime();
            if (appRuntime == null) {
                HookLog.info("灰条插入失败：QQ AppRuntime 尚未就绪");
                return;
            }

            String selfUid = getSelfUid(appRuntime);
            String peerUid = resolvePeerUid(event, selfUid);
            if (peerUid == null || peerUid.isEmpty() || "?".equals(peerUid)) {
                HookLog.info("灰条插入失败：无法确定会话 peer，事件=" + event.describe());
                return;
            }

            int chatType = event.kind() == NtRecallParser.Event.Kind.GROUP
                    ? CHAT_TYPE_GROUP
                    : CHAT_TYPE_C2C;
            long busiId = chatType == CHAT_TYPE_GROUP ? BUSI_ID_GROUP : BUSI_ID_C2C;
            String text = formatTemplate(template, event, selfUid, peerUid);
            String json = buildGrayTipJson(text);

            Object service = getKernelMsgService(appRuntime);
            Object contact = createContact(chatType, peerUid);
            Object element = createJsonGrayElement(busiId, json, text);
            Method addMethod = findAddGrayTipMethod(service, contact, element);
            Object callback = createCallback(addMethod.getParameterTypes()[4]);
            addMethod.invoke(service, contact, element, true, true, callback);

            HookLog.info("已插入本地撤回灰条：chatType=" + chatType
                    + ", peer=" + peerUid + ", seq=" + event.msgSeq()
                    + ", text=" + text);
        } catch (Throwable throwable) {
            HookLog.error("插入本地撤回灰条失败", throwable);
        }
    }

    private Object getAppRuntime() throws ReflectiveOperationException {
        Class<?> mobileQQClass = Class.forName("mqq.app.MobileQQ", false, classLoader);
        Field singletonField = findField(mobileQQClass, "sMobileQQ");
        Object mobileQQ = singletonField.get(null);
        if (mobileQQ == null) {
            return null;
        }
        Field runtimeField = findField(mobileQQ.getClass(), "mAppRuntime");
        return runtimeField.get(mobileQQ);
    }

    private String getSelfUid(Object appRuntime) {
        try {
            Method getAccount = appRuntime.getClass().getMethod("getAccount");
            String uin = String.valueOf(getAccount.invoke(appRuntime));
            Class<?> relationApi = Class.forName(
                    "com.tencent.relation.common.api.IRelationNTUinAndUidApi",
                    false,
                    classLoader
            );
            Class<?> qRoute = Class.forName("com.tencent.mobileqq.qroute.QRoute", false, classLoader);
            Method api = qRoute.getMethod("api", Class.class);
            Object relation = api.invoke(null, relationApi);
            Method getUid = relationApi.getMethod("getUidFromUin", String.class);
            Object uid = getUid.invoke(relation, uin);
            return uid instanceof String ? (String) uid : null;
        } catch (Throwable throwable) {
            HookLog.debug("无法解析当前账号 UID，将使用撤回来源作为 C2C peer：" + throwable);
            return null;
        }
    }

    private Object getKernelMsgService(Object appRuntime) throws ReflectiveOperationException {
        Class<?> kernelServiceApi = Class.forName(
                "com.tencent.qqnt.kernel.api.IKernelService",
                false,
                classLoader
        );
        Method getRuntimeService = appRuntime.getClass().getMethod(
                "getRuntimeService",
                Class.class,
                String.class
        );
        Object kernelService = getRuntimeService.invoke(appRuntime, kernelServiceApi, "");
        if (kernelService == null) {
            throw new IllegalStateException("IKernelService is null");
        }
        Method getMsgService = kernelService.getClass().getMethod("getMsgService");
        Object msgServiceWrapper = getMsgService.invoke(kernelService);
        if (msgServiceWrapper == null) {
            throw new IllegalStateException("MsgService wrapper is null");
        }
        try {
            Method getService = msgServiceWrapper.getClass().getMethod("getService");
            Object service = getService.invoke(msgServiceWrapper);
            if (service != null) {
                return service;
            }
        } catch (NoSuchMethodException ignored) {
            // Older variants expose a differently named zero-argument getter.
        }
        for (Method method : msgServiceWrapper.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getReturnType().getName().contains("IKernelMsgService")) {
                Object service = method.invoke(msgServiceWrapper);
                if (service != null) {
                    return service;
                }
            }
        }
        throw new NoSuchMethodException("Unable to obtain IKernelMsgService");
    }

    private Object createContact(int chatType, String peerUid) throws ReflectiveOperationException {
        Class<?> contactClass = loadKernelClass("Contact");
        Constructor<?> constructor = contactClass.getDeclaredConstructor(
                int.class,
                String.class,
                String.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(chatType, peerUid, "");
    }

    private Object createJsonGrayElement(long busiId, String json, String summary)
            throws ReflectiveOperationException {
        Class<?> elementClass = loadKernelClass("JsonGrayElement");
        for (Constructor<?> constructor : elementClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 5
                    || !(parameters[0] == long.class || parameters[0] == Long.class)
                    || parameters[1] != String.class
                    || parameters[2] != String.class
                    || !(parameters[3] == boolean.class || parameters[3] == Boolean.class)) {
                continue;
            }
            constructor.setAccessible(true);
            return constructor.newInstance(busiId, json, summary, false, null);
        }
        throw new NoSuchMethodException("JsonGrayElement(long,String,String,boolean,*)");
    }

    private Class<?> loadKernelClass(String simpleName) throws ClassNotFoundException {
        String[] prefixes = {
                "com.tencent.qqnt.kernelpublic.nativeinterface.",
                "com.tencent.qqnt.kernel.nativeinterface."
        };
        ClassNotFoundException last = null;
        for (String prefix : prefixes) {
            try {
                return Class.forName(prefix + simpleName, false, classLoader);
            } catch (ClassNotFoundException exception) {
                last = exception;
            }
        }
        throw last == null ? new ClassNotFoundException(simpleName) : last;
    }

    private Method findAddGrayTipMethod(Object service, Object contact, Object element)
            throws NoSuchMethodException {
        for (Method method : service.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"addLocalJsonGrayTipMsg".equals(method.getName())
                    || parameters.length != 5
                    || !parameters[0].isAssignableFrom(contact.getClass())
                    || !parameters[1].isAssignableFrom(element.getClass())) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        throw new NoSuchMethodException("IKernelMsgService.addLocalJsonGrayTipMsg");
    }

    private Object createCallback(Class<?> callbackType) {
        if (callbackType == null || !callbackType.isInterface()) {
            return null;
        }
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{callbackType},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private String resolvePeerUid(NtRecallParser.Event event, String selfUid) {
        if (event.kind() == NtRecallParser.Event.Kind.GROUP) {
            return event.peer();
        }
        String fromUid = event.operator();
        String toUid = event.target();
        if (selfUid != null && selfUid.equals(fromUid)) {
            return toUid;
        }
        return fromUid != null && !fromUid.isEmpty() ? fromUid : toUid;
    }

    static String formatTemplate(String template, NtRecallParser.Event event,
            String selfUid, String resolvedPeer) {
        String operatorLabel;
        if (event.kind() == NtRecallParser.Event.Kind.C2C) {
            operatorLabel = selfUid != null && selfUid.equals(event.operator()) ? "你" : "对方";
        } else if (selfUid != null && selfUid.equals(event.operator())) {
            operatorLabel = "你";
        } else if (event.author() != null && event.operator() != null
                && !event.operator().equals(event.author())) {
            operatorLabel = "管理员";
        } else {
            operatorLabel = "该用户";
        }

        String authorLabel;
        if (selfUid != null && selfUid.equals(event.author())) {
            authorLabel = "你";
        } else if (event.kind() == NtRecallParser.Event.Kind.C2C) {
            authorLabel = operatorLabel;
        } else {
            authorLabel = "该成员";
        }

        String result = normalizeTemplate(template)
                .replace("{operator}", operatorLabel)
                .replace("{author}", authorLabel)
                .replace("{operator_uid}", safe(event.operator()))
                .replace("{author_uid}", safe(event.author()))
                .replace("{peer}", safe(resolvedPeer))
                .replace("{seq}", Long.toString(event.msgSeq()))
                .replace("{uid}", Long.toString(event.msgUid()))
                .replace("{type}", event.kind() == NtRecallParser.Event.Kind.GROUP ? "群聊" : "私聊");
        if (result.length() > ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH) {
            result = result.substring(0, ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH);
        }
        return result;
    }

    private static String buildGrayTipJson(String text) throws Exception {
        JSONObject item = new JSONObject();
        item.put("txt", text);
        item.put("type", "nor");
        JSONArray items = new JSONArray();
        items.put(item);
        JSONObject root = new JSONObject();
        root.put("align", "center");
        root.put("items", items);
        return root.toString();
    }

    private static String normalizeTemplate(String template) {
        String value = template == null ? "" : template.trim();
        if (value.isEmpty()) {
            value = ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE;
        }
        return value.length() <= ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH
                ? value
                : value.substring(0, ModulePrefs.MAX_GRAY_TIP_TEMPLATE_LENGTH);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "?" : value;
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
}
