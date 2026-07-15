package com.huanhren.qqantirevoke.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class RevokeContext {
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override
        protected State initialValue() {
            return new State();
        }
    };

    private static final String[] ID_FIELD_HINTS = {
            "msguid", "uniseq", "shmsgseq", "msgseq", "seq", "msgid", "time", "frienduin", "istroop"
    };

    private RevokeContext() {}

    static void enter(Object revokeInfoArgument) {
        State state = STATE.get();
        state.depth++;
        if (state.depth == 1) {
            state.identifiers.clear();
            state.identifiers.addAll(extractFromArgument(revokeInfoArgument));
        }
    }

    static void exit() {
        State state = STATE.get();
        state.depth = Math.max(0, state.depth - 1);
        if (state.depth == 0) {
            state.identifiers.clear();
            STATE.remove();
        }
    }

    static boolean isInsideRevoke() {
        return STATE.get().depth > 0;
    }

    static Set<String> currentIdentifiers() {
        return Collections.unmodifiableSet(STATE.get().identifiers);
    }

    static MatchResult matchesMessage(Object message) {
        Set<String> expected = STATE.get().identifiers;
        Set<String> actual = extractIdentifiers(message);
        if (expected.isEmpty() || actual.isEmpty()) {
            return new MatchResult(false, expected.isEmpty(), actual.isEmpty(), Collections.<String>emptySet());
        }
        Set<String> intersection = new HashSet<String>(expected);
        intersection.retainAll(actual);
        return new MatchResult(!intersection.isEmpty(), false, false, intersection);
    }

    static BatchMatchResult matchesBatch(Object listArgument) {
        if (!(listArgument instanceof Collection)) {
            return new BatchMatchResult(false, 0, 0);
        }
        Collection<?> collection = (Collection<?>) listArgument;
        if (collection.isEmpty()) {
            return new BatchMatchResult(false, 0, 0);
        }
        int inspected = 0;
        int matched = 0;
        for (Object item : collection) {
            if (item == null) continue;
            inspected++;
            if (matchesMessage(item).matched()) matched++;
        }
        return new BatchMatchResult(inspected > 0 && inspected == matched, inspected, matched);
    }

    private static Set<String> extractFromArgument(Object argument) {
        if (argument instanceof Collection) {
            Set<String> result = new HashSet<String>();
            for (Object item : (Collection<?>) argument) {
                result.addAll(extractIdentifiers(item));
            }
            return result;
        }
        return extractIdentifiers(argument);
    }

    private static Set<String> extractIdentifiers(Object object) {
        if (object == null) return Collections.emptySet();
        Set<String> result = new HashSet<String>();
        Class<?> type = object.getClass();
        int levels = 0;
        while (type != null && type != Object.class && levels++ < 8) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !isIdentifierField(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    String normalized = normalizeValue(field.get(object));
                    if (normalized != null) {
                        result.add(normalizeName(field.getName()) + "=" + normalized);
                    }
                } catch (Throwable ignored) {
                    // Ignore inaccessible fields so QQ keeps running.
                }
            }
            type = type.getSuperclass();
        }
        return result;
    }

    private static boolean isIdentifierField(String fieldName) {
        String normalized = normalizeName(fieldName);
        for (String hint : ID_FIELD_HINTS) {
            if (normalized.equals(hint) || normalized.contains(hint)) return true;
        }
        return false;
    }

    private static String normalizeName(String value) {
        return value.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            long number = ((Number) value).longValue();
            return number == 0L ? null : Long.toString(number);
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            return text.isEmpty() || "0".equals(text) ? null : text;
        }
        return null;
    }

    private static final class State {
        int depth;
        final Set<String> identifiers = new HashSet<String>();
    }

    static final class MatchResult {
        private final boolean matched;
        private final boolean expectedEmpty;
        private final boolean actualEmpty;
        private final Set<String> intersection;

        MatchResult(boolean matched, boolean expectedEmpty, boolean actualEmpty, Set<String> intersection) {
            this.matched = matched;
            this.expectedEmpty = expectedEmpty;
            this.actualEmpty = actualEmpty;
            this.intersection = intersection;
        }

        boolean matched() { return matched; }
        boolean expectedEmpty() { return expectedEmpty; }
        boolean actualEmpty() { return actualEmpty; }
        Set<String> intersection() { return intersection; }
    }

    static final class BatchMatchResult {
        private final boolean allMatched;
        private final int inspected;
        private final int matched;

        BatchMatchResult(boolean allMatched, int inspected, int matched) {
            this.allMatched = allMatched;
            this.inspected = inspected;
            this.matched = matched;
        }

        boolean allMatched() { return allMatched; }
        int inspected() { return inspected; }
        int matched() { return matched; }
    }
}
