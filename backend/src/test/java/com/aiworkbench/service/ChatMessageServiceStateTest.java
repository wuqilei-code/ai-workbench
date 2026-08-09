package com.aiworkbench.service;

import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.OpencodeEventRouter;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatMessageService 看门狗相关状态机测试:
 * hasRunningTool(在途工具判定)与 markStreamingAborted(streaming → aborted 收口)。
 * 与 StuckToolWatcherTest 对拍,确保「工具卡死」判定与收口逻辑正确。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatMessageServiceStateTest {

    @Mock private ChatMessageMapper chatMessageMapper;
    @Mock private ChatSessionService chatSessionService;
    @Mock private OpencodeClient opencodeClient;
    @Mock private OpencodeEventRouter eventRouter;

    private ChatMessageService service;

    /** MyBatis-Plus lambda wrapper 需要实体表信息缓存;无 Spring 上下文时手动初始化一次 */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChatMessage.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChatMessageService(chatMessageMapper, chatSessionService, opencodeClient,
                eventRouter, new ObjectMapper(), null);
    }

    private ChatMessage assistantMsg(String partsJson) {
        ChatMessage m = new ChatMessage();
        m.setId(100L);
        m.setSessionId(116L);
        m.setUserId(32L);
        m.setRole("assistant");
        m.setStatus("streaming");
        m.setParts(partsJson);
        return m;
    }

    // ---------- hasRunningTool ----------

    @Test
    void hasRunningTool_trueWhenToolRunning() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                assistantMsg("{\"toolCalls\":[{\"partId\":\"p1\",\"tool\":\"bash\",\"status\":\"running\",\"input\":{\"command\":\"sleep 999\"}}]}")
        ));

        assertTrue(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenAllCompleted() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                assistantMsg("{\"toolCalls\":[{\"partId\":\"p1\",\"tool\":\"bash\",\"status\":\"completed\"}]}")
        ));

        assertFalse(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenNoMessages() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of());
        assertFalse(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenNullParts() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(assistantMsg(null)));
        assertFalse(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenBlankParts() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(assistantMsg("  ")));
        assertFalse(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenPartsNotJson() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(assistantMsg("not-json{")));
        // 解析失败的 parts 不应导致判定异常,返回 false
        assertFalse(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenNoToolCallsField() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                assistantMsg("{\"stepFinish\":{\"reason\":\"done\"}}")
        ));
        assertFalse(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_trueWhenRunningIsSpanAcrossOlderMessages() {
        // 最新消息已完成,但前一条还有 running 工具(如卡死的 bash) → 仍判定在途
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                assistantMsg("{\"toolCalls\":[{\"partId\":\"p2\",\"tool\":\"bash\",\"status\":\"completed\"}]}"),
                assistantMsg("{\"toolCalls\":[{\"partId\":\"p1\",\"tool\":\"bash\",\"status\":\"running\"}]}")
        ));

        assertTrue(service.hasRunningTool(116L));
    }

    @Test
    void hasRunningTool_falseWhenStatusNull() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                assistantMsg("{\"toolCalls\":[{\"partId\":\"p1\",\"tool\":\"bash\"}]}")
        ));
        assertFalse(service.hasRunningTool(116L));
    }

    // ---------- markStreamingAborted ----------

    @Test
    void markStreamingAborted_returnsUpdatedRows() {
        when(chatMessageMapper.update(isNull(), any())).thenReturn(2);
        assertEquals(2, service.markStreamingAborted(116L));
    }

    @Test
    void markStreamingAborted_zeroWhenNothingStreaming() {
        when(chatMessageMapper.update(isNull(), any())).thenReturn(0);
        assertEquals(0, service.markStreamingAborted(116L));
    }

    @Test
    void markStreamingAborted_targetsAssistantStreamingMessages() {
        when(chatMessageMapper.update(isNull(), any())).thenReturn(1);

        service.markStreamingAborted(116L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ChatMessage>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chatMessageMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ChatMessage> wrapper = (LambdaUpdateWrapper<ChatMessage>) captor.getValue();

        String sql = wrapper.getSqlSegment();
        String setSql = wrapper.getSqlSet();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(sql.contains("session_id"), "应限定会话: " + sql);
        assertTrue(params.containsValue("assistant"), "应限定 assistant 角色: " + sql + " params=" + params);
        assertTrue(params.containsValue("streaming"), "应限定 streaming 状态: " + sql + " params=" + params);
        assertTrue(params.containsValue("aborted"), "应把状态置为 aborted: " + setSql + " params=" + params);
    }
}