package com.huanhren.qqantirevoke.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class MethodSelectors {
    private MethodSelectors() {}

    static List<Method> revokeEntries(Class<?> type) {
        return select(type, "k", method -> {
            Class<?>[] p = method.getParameterTypes();
            return p.length == 2
                    && java.util.List.class.isAssignableFrom(p[0])
                    && p[1] == boolean.class;
        });
    }

    static List<Method> singleRemoveMethods(Class<?> type) {
        return select(type, "V", method -> {
            Class<?>[] p = method.getParameterTypes();
            return p.length == 3
                    && looksLikeMessageRecord(p[0])
                    && p[1] == boolean.class
                    && p[2] == boolean.class;
        });
    }

    static List<Method> batchRemoveMethods(Class<?> type) {
        return select(type, "Z", method -> {
            Class<?>[] p = method.getParameterTypes();
            return p.length == 3
                    && java.util.List.class.isAssignableFrom(p[0])
                    && p[1] == boolean.class
                    && p[2] == boolean.class;
        });
    }

    private static boolean looksLikeMessageRecord(Class<?> type) {
        String name = type.getName();
        return name.contains("MessageRecord") || name.startsWith("com.tencent.mobileqq.data.");
    }

    private static List<Method> select(Class<?> type, String name, Predicate predicate) {
        List<Method> result = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.isSynthetic() || Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            if (predicate.test(method)) {
                method.setAccessible(true);
                result.add(method);
            }
        }
        return result;
    }

    static String signature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.toString(method.getParameterTypes())
                + " -> " + method.getReturnType().getTypeName();
    }

    static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == void.class || !returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return '\0';
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }

    private interface Predicate {
        boolean test(Method method);
    }
}
