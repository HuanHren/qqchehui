package com.huanhren.qqantirevoke.hook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class NtRecallParserTest {

    @Test
    public void parsesC2cOlPushRecall() {
        byte[] info = message(
                bytesField(1, "u_from".getBytes(StandardCharsets.UTF_8)),
                bytesField(2, "u_to".getBytes(StandardCharsets.UTF_8)),
                varintField(3, 33),
                varintField(4, 44),
                varintField(5, 55),
                varintField(6, 66),
                varintField(20, 77)
        );
        byte[] recall = message(bytesField(1, info));
        byte[] payload = buildOlPush(528, 138, recall);

        NtRecallParser.ParseResult result = NtRecallParser.parseOlPush(payload);

        assertTrue(result.isRecall());
        assertEquals(1, result.events().size());
        NtRecallParser.Event event = result.events().get(0);
        assertEquals(NtRecallParser.Event.Kind.C2C, event.kind());
        assertEquals(44, event.msgUid());
        assertEquals(77, event.msgSeq());
    }

    @Test
    public void parsesGroupOlPushRecall() {
        byte[] info = message(
                varintField(1, 1234),
                varintField(2, 4567),
                varintField(3, 8910),
                bytesField(6, "author_uid".getBytes(StandardCharsets.UTF_8))
        );
        byte[] recallInfo = message(
                bytesField(1, "operator_uid".getBytes(StandardCharsets.UTF_8)),
                bytesField(3, info)
        );
        byte[] groupRecall = message(
                varintField(1, 7),
                varintField(4, 998877),
                bytesField(11, recallInfo)
        );
        byte[] prefixed = new byte[groupRecall.length + 7];
        System.arraycopy(groupRecall, 0, prefixed, 7, groupRecall.length);
        byte[] payload = buildOlPush(732, 17, prefixed);

        NtRecallParser.ParseResult result = NtRecallParser.parseOlPush(payload);

        assertTrue(result.isRecall());
        assertEquals(1, result.events().size());
        NtRecallParser.Event event = result.events().get(0);
        assertEquals(NtRecallParser.Event.Kind.GROUP, event.kind());
        assertEquals(1234, event.msgSeq());
    }

    @Test
    public void stripsOnlyInfoSyncRecallField() throws Exception {
        byte[] groupPayload = buildMessage(732, 17, new byte[8]);
        byte[] syncBody = message(
                bytesField(2, "998877".getBytes(StandardCharsets.UTF_8)),
                bytesField(8, groupPayload)
        );
        byte[] syncRecall = message(bytesField(4, syncBody));
        byte[] keep = new byte[]{1, 2, 3, 4};
        byte[] infoSync = message(
                bytesField(7, keep),
                bytesField(8, syncRecall),
                varintField(10, 1)
        );

        NtRecallParser.ParseResult result = NtRecallParser.parseInfoSync(infoSync);
        byte[] stripped = NtRecallParser.stripInfoSyncRecall(infoSync);

        assertTrue(result.isRecall());
        assertTrue(stripped.length < infoSync.length);
        assertArrayEquals(keep, ProtoWire.firstBytes(stripped, 7));
        assertNull(ProtoWire.firstBytes(stripped, 8));
        assertEquals(1, ProtoWire.firstVarint(stripped, 10, 0));
    }

    @Test
    public void ignoresNonRecallOlPush() {
        byte[] payload = buildOlPush(1, 2, new byte[]{3});
        NtRecallParser.ParseResult result = NtRecallParser.parseOlPush(payload);
        assertFalse(result.isRecall());
        assertNotNull(result.describe());
    }

    private static byte[] buildOlPush(int type, int subType, byte[] content) {
        return message(bytesField(1, buildMessage(type, subType, content)));
    }

    private static byte[] buildMessage(int type, int subType, byte[] content) {
        byte[] head = message(varintField(1, type), varintField(2, subType));
        byte[] body = message(bytesField(2, content));
        return message(bytesField(2, head), bytesField(3, body));
    }

    private static byte[] message(byte[]... fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            output.write(field, 0, field.length);
        }
        return output.toByteArray();
    }

    private static byte[] varintField(int fieldNumber, long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) fieldNumber << 3));
        writeVarint(output, value);
        return output.toByteArray();
    }

    private static byte[] bytesField(int fieldNumber, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) fieldNumber << 3) | 2);
        writeVarint(output, value.length);
        output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }
}
