package com.huanhren.qqantirevoke.hook;

import static org.junit.Assert.assertEquals;

import com.huanhren.qqantirevoke.ModulePrefs;

import org.junit.Test;

public class NtGrayTipInjectorTest {

    @Test
    public void formatsDefaultGroupTemplate() {
        NtRecallParser.Event event = NtRecallParser.Event.group(
                "885727513",
                "u_operator",
                "u_operator",
                12345,
                67890,
                100,
                7
        );

        assertEquals(
                "该用户尝试撤回一条消息",
                NtGrayTipInjector.formatTemplate(
                        ModulePrefs.DEFAULT_GRAY_TIP_TEMPLATE,
                        event,
                        null,
                        "885727513"
                )
        );
    }

    @Test
    public void formatsAdminAndSequenceVariables() {
        NtRecallParser.Event event = NtRecallParser.Event.group(
                "885727513",
                "u_admin",
                "u_member",
                223344,
                99,
                100,
                7
        );

        assertEquals(
                "管理员尝试撤回该成员的消息 [seq=223344]",
                NtGrayTipInjector.formatTemplate(
                        "{operator}尝试撤回{author}的消息 [seq={seq}]",
                        event,
                        "u_self",
                        "885727513"
                )
        );
    }

    @Test
    public void formatsC2cSelfRecall() {
        NtRecallParser.Event event = NtRecallParser.Event.c2c(
                "u_self",
                "u_peer",
                1,
                2,
                3,
                4,
                5
        );

        assertEquals(
                "你在私聊中尝试撤回消息",
                NtGrayTipInjector.formatTemplate(
                        "{operator}在{type}中尝试撤回消息",
                        event,
                        "u_self",
                        "u_peer"
                )
        );
    }
}
