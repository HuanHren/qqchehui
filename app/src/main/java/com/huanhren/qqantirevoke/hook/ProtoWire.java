package com.huanhren.qqantirevoke.hook;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class ProtoWire {
    private static final int MAX_LENGTH_DELIMITED_BYTES = 16 * 1024 * 1024;

    private ProtoWire() {}

    static long firstVarint(byte[] data, int fieldNumber, long defaultValue) {
        if (data == null) return defaultValue;
        try {
            int position = 0;
            while (position < data.length) {
                Varint tag = readVarint(data, position);
                position = tag.next;
                int currentField = (int) (tag.value >>> 3);
                int wireType = (int) (tag.value & 7);
                if (currentField == fieldNumber && wireType == 0) {
                    return readVarint(data, position).value;
                }
                position = skipValue(data, position, wireType);
            }
        } catch (ParseException ignored) {
            return defaultValue;
        }
        return defaultValue;
    }

    static byte[] firstBytes(byte[] data, int fieldNumber) {
        List<byte[]> values = allBytes(data, fieldNumber, 1);
        return values.isEmpty() ? null : values.get(0);
    }

    static List<byte[]> allBytes(byte[] data, int fieldNumber) {
        return allBytes(data, fieldNumber, Integer.MAX_VALUE);
    }

    static String firstString(byte[] data, int fieldNumber) {
        byte[] value = firstBytes(data, fieldNumber);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    static boolean hasField(byte[] data, int fieldNumber) {
        if (data == null) return false;
        try {
            int position = 0;
            while (position < data.length) {
                Varint tag = readVarint(data, position);
                position = tag.next;
                int currentField = (int) (tag.value >>> 3);
                int wireType = (int) (tag.value & 7);
                if (currentField == fieldNumber) {
                    return true;
                }
                position = skipValue(data, position, wireType);
            }
        } catch (ParseException ignored) {
            return false;
        }
        return false;
    }

    static byte[] removeField(byte[] data, int fieldNumber) throws ParseException {
        if (data == null || data.length == 0) return data;
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        int position = 0;
        while (position < data.length) {
            int start = position;
            Varint tag = readVarint(data, position);
            position = tag.next;
            int currentField = (int) (tag.value >>> 3);
            int wireType = (int) (tag.value & 7);
            if (currentField <= 0) {
                throw new ParseException("invalid field number " + currentField);
            }
            position = skipValue(data, position, wireType);
            if (currentField != fieldNumber) {
                output.write(data, start, position - start);
            }
        }
        return output.toByteArray();
    }

    private static List<byte[]> allBytes(byte[] data, int fieldNumber, int limit) {
        if (data == null || limit <= 0) return Collections.emptyList();
        List<byte[]> result = new ArrayList<>();
        try {
            int position = 0;
            while (position < data.length && result.size() < limit) {
                Varint tag = readVarint(data, position);
                position = tag.next;
                int currentField = (int) (tag.value >>> 3);
                int wireType = (int) (tag.value & 7);
                if (wireType == 2) {
                    Varint length = readVarint(data, position);
                    int start = length.next;
                    int size = checkedLength(length.value, data.length - start);
                    int end = start + size;
                    if (currentField == fieldNumber) {
                        result.add(Arrays.copyOfRange(data, start, end));
                    }
                    position = end;
                } else {
                    position = skipValue(data, position, wireType);
                }
            }
        } catch (ParseException ignored) {
            return result;
        }
        return result;
    }

    private static int skipValue(byte[] data, int position, int wireType) throws ParseException {
        switch (wireType) {
            case 0:
                return readVarint(data, position).next;
            case 1:
                return checkedAdvance(data, position, 8);
            case 2:
                Varint length = readVarint(data, position);
                int size = checkedLength(length.value, data.length - length.next);
                return length.next + size;
            case 5:
                return checkedAdvance(data, position, 4);
            default:
                throw new ParseException("unsupported wire type " + wireType);
        }
    }

    private static int checkedAdvance(byte[] data, int position, int count) throws ParseException {
        if (position < 0 || count < 0 || position > data.length - count) {
            throw new ParseException("truncated field");
        }
        return position + count;
    }

    private static int checkedLength(long rawLength, int remaining) throws ParseException {
        if (rawLength < 0 || rawLength > Integer.MAX_VALUE) {
            throw new ParseException("invalid length " + rawLength);
        }
        int length = (int) rawLength;
        if (length > MAX_LENGTH_DELIMITED_BYTES || length > remaining) {
            throw new ParseException("length exceeds input: " + length);
        }
        return length;
    }

    private static Varint readVarint(byte[] data, int position) throws ParseException {
        long value = 0L;
        int shift = 0;
        int cursor = position;
        while (cursor < data.length && shift < 64) {
            int current = data[cursor++] & 0xff;
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                return new Varint(value, cursor);
            }
            shift += 7;
        }
        throw new ParseException("truncated or oversized varint at " + position);
    }

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private static final class Varint {
        final long value;
        final int next;

        Varint(long value, int next) {
            this.value = value;
            this.next = next;
        }
    }
}
