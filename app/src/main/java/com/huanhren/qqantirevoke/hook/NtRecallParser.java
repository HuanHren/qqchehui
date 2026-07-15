package com.huanhren.qqantirevoke.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class NtRecallParser {
    static final String CMD_OL_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    static final String CMD_INFO_SYNC = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush";

    private static final int C2C_RECALL_TYPE = 528;
    private static final int C2C_RECALL_SUB_TYPE = 138;
    private static final int GROUP_RECALL_TYPE = 732;
    private static final int GROUP_RECALL_SUB_TYPE = 17;

    private NtRecallParser() {}

    static ParseResult parseOlPush(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return ParseResult.notRecall("empty OlPush payload");
        }
        byte[] message = ProtoWire.firstBytes(payload, 1);
        if (message == null) {
            return ParseResult.notRecall("MsgPush.message missing");
        }
        Event event = parseMessage(message, null);
        if (event == null) {
            return ParseResult.notRecall("message is not a recall event");
        }
        return ParseResult.recall(false, Collections.singletonList(event), "OlPush recall");
    }

    static ParseResult parseInfoSync(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return ParseResult.notRecall("empty InfoSync payload");
        }
        byte[] syncRecall = ProtoWire.firstBytes(payload, 8);
        if (syncRecall == null) {
            return ParseResult.notRecall("InfoSyncPush.sync_msg_recall missing");
        }

        List<Event> events = new ArrayList<>();
        for (byte[] body : ProtoWire.allBytes(syncRecall, 4)) {
            String peerUid = ProtoWire.firstString(body, 2);
            for (byte[] message : ProtoWire.allBytes(body, 8)) {
                Event event = parseMessage(message, peerUid);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        if (events.isEmpty()) {
            events.add(Event.syncUnknown());
        }
        return ParseResult.recall(true, events, "InfoSync recall container");
    }

    static byte[] stripInfoSyncRecall(byte[] payload) throws ProtoWire.ParseException {
        return ProtoWire.removeField(payload, 8);
    }

    private static Event parseMessage(byte[] message, String peerUidHint) {
        byte[] contentHead = ProtoWire.firstBytes(message, 2);
        if (contentHead == null) {
            return null;
        }
        int type = (int) ProtoWire.firstVarint(contentHead, 1, -1);
        int subType = (int) ProtoWire.firstVarint(contentHead, 2, 0);
        if (!isRecallType(type, subType)) {
            return null;
        }

        byte[] body = ProtoWire.firstBytes(message, 3);
        byte[] content = body == null ? null : ProtoWire.firstBytes(body, 2);
        if (type == C2C_RECALL_TYPE && subType == C2C_RECALL_SUB_TYPE) {
            return parseC2c(content);
        }
        return parseGroup(content, peerUidHint);
    }

    private static Event parseC2c(byte[] content) {
        if (content == null) {
            return Event.c2c(null, null, 0, 0, 0, 0, 0);
        }
        List<byte[]> infos = ProtoWire.allBytes(content, 1);
        if (infos.isEmpty()) {
            return Event.c2c(null, null, 0, 0, 0, 0, 0);
        }
        byte[] info = infos.get(0);
        return Event.c2c(
                ProtoWire.firstString(info, 1),
                ProtoWire.firstString(info, 2),
                ProtoWire.firstVarint(info, 4, 0),
                ProtoWire.firstVarint(info, 20, 0),
                ProtoWire.firstVarint(info, 3, 0),
                ProtoWire.firstVarint(info, 6, 0),
                ProtoWire.firstVarint(info, 5, 0)
        );
    }

    private static Event parseGroup(byte[] content, String peerUidHint) {
        if (content == null || content.length <= 7) {
            return Event.group(peerUidHint, null, null, 0, 0, 0, 0);
        }
        byte[] groupPayload = Arrays.copyOfRange(content, 7, content.length);
        long opType = ProtoWire.firstVarint(groupPayload, 1, 0);
        long groupCode = ProtoWire.firstVarint(groupPayload, 4, 0);
        byte[] recallInfo = ProtoWire.firstBytes(groupPayload, 11);
        String operatorUid = recallInfo == null ? null : ProtoWire.firstString(recallInfo, 1);
        List<byte[]> infos = recallInfo == null
                ? Collections.emptyList()
                : ProtoWire.allBytes(recallInfo, 3);
        if (infos.isEmpty()) {
            return Event.group(
                    groupCode == 0 ? peerUidHint : Long.toString(groupCode),
                    operatorUid,
                    null,
                    0,
                    0,
                    0,
                    opType
            );
        }
        byte[] info = infos.get(0);
        return Event.group(
                groupCode == 0 ? peerUidHint : Long.toString(groupCode),
                operatorUid,
                ProtoWire.firstString(info, 6),
                ProtoWire.firstVarint(info, 1, 0),
                ProtoWire.firstVarint(info, 3, 0),
                ProtoWire.firstVarint(info, 2, 0),
                opType
        );
    }

    private static boolean isRecallType(int type, int subType) {
        return (type == C2C_RECALL_TYPE && subType == C2C_RECALL_SUB_TYPE)
                || (type == GROUP_RECALL_TYPE && subType == GROUP_RECALL_SUB_TYPE);
    }

    static final class ParseResult {
        private final boolean recall;
        private final boolean syncContainer;
        private final List<Event> events;
        private final String reason;

        private ParseResult(boolean recall, boolean syncContainer, List<Event> events, String reason) {
            this.recall = recall;
            this.syncContainer = syncContainer;
            this.events = events;
            this.reason = reason;
        }

        static ParseResult notRecall(String reason) {
            return new ParseResult(false, false, Collections.emptyList(), reason);
        }

        static ParseResult recall(boolean syncContainer, List<Event> events, String reason) {
            return new ParseResult(true, syncContainer, Collections.unmodifiableList(new ArrayList<>(events)), reason);
        }

        boolean isRecall() {
            return recall;
        }

        boolean isSyncContainer() {
            return syncContainer;
        }

        List<Event> events() {
            return events;
        }

        String describe() {
            if (!recall) {
                return reason;
            }
            StringBuilder builder = new StringBuilder(reason)
                    .append(", eventCount=")
                    .append(events.size());
            for (int i = 0; i < events.size() && i < 3; i++) {
                builder.append(" | ").append(events.get(i).describe());
            }
            if (events.size() > 3) {
                builder.append(" | …");
            }
            return builder.toString();
        }
    }

    static final class Event {
        enum Kind { C2C, GROUP, SYNC_UNKNOWN }

        private final Kind kind;
        private final String peer;
        private final String operator;
        private final String author;
        private final long msgUid;
        private final long msgSeq;
        private final long clientSeq;
        private final long randomId;
        private final long timestamp;
        private final long opType;

        private Event(Kind kind, String peer, String operator, String author,
                long msgUid, long msgSeq, long clientSeq, long randomId,
                long timestamp, long opType) {
            this.kind = kind;
            this.peer = peer;
            this.operator = operator;
            this.author = author;
            this.msgUid = msgUid;
            this.msgSeq = msgSeq;
            this.clientSeq = clientSeq;
            this.randomId = randomId;
            this.timestamp = timestamp;
            this.opType = opType;
        }

        static Event c2c(String fromUid, String toUid, long msgUid, long msgSeq,
                long clientSeq, long randomId, long timestamp) {
            String peer = safe(fromUid) + "->" + safe(toUid);
            return new Event(Kind.C2C, peer, fromUid, fromUid, msgUid, msgSeq,
                    clientSeq, randomId, timestamp, 0);
        }

        static Event group(String groupCode, String operatorUid, String authorUid,
                long msgSeq, long randomId, long timestamp, long opType) {
            return new Event(Kind.GROUP, groupCode, operatorUid, authorUid, 0,
                    msgSeq, 0, randomId, timestamp, opType);
        }

        static Event syncUnknown() {
            return new Event(Kind.SYNC_UNKNOWN, null, null, null, 0, 0, 0, 0, 0, 0);
        }

        Kind kind() {
            return kind;
        }

        long msgSeq() {
            return msgSeq;
        }

        long msgUid() {
            return msgUid;
        }

        String describe() {
            if (kind == Kind.C2C) {
                return "C2C peer=" + safe(peer)
                        + ", uid=" + msgUid
                        + ", seq=" + msgSeq
                        + ", cseq=" + clientSeq
                        + ", random=" + randomId
                        + ", time=" + timestamp;
            }
            if (kind == Kind.GROUP) {
                return "GROUP code=" + safe(peer)
                        + ", operator=" + safe(operator)
                        + ", author=" + safe(author)
                        + ", seq=" + msgSeq
                        + ", random=" + randomId
                        + ", time=" + timestamp
                        + ", opType=" + opType;
            }
            return "SYNC_UNKNOWN";
        }

        private static String safe(String value) {
            return value == null || value.isEmpty() ? "?" : value;
        }
    }
}
