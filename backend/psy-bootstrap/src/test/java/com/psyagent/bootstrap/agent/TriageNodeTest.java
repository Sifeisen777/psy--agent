package com.psyagent.bootstrap.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分诊规则的白盒测试。
 * <p>
 * 为什么这组用例值得单独写：分诊是全链路里唯一"写错就会影响人"的 if-else。
 * 接口测试能验证它这次判对了，但跑一次要几秒、要起服务、要真模型；
 * 这里毫秒级、不依赖任何外部环境，改规则时能立刻知道哪条边界被碰坏了。
 * <p>
 * 用例全部来自真实踩过的问题，不是凭空构造的字符串。
 */
class TriageNodeTest {

    private final TriageNode node = new TriageNode();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "？？？", "。。。", "~"})
    @DisplayName("空白与纯标点按寒暄处理，不抛异常")
    void blankOrPunctuationIsSmalltalk(String message) {
        assertThat(TriageNode.route(message)).isEqualTo(AgentRoute.SMALLTALK);
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({
            // 简短寒暄：长度是主要判据
            "你好,           SMALLTALK",
            "你好呀,         SMALLTALK",
            "在吗,           SMALLTALK",
            "嗯,             SMALLTALK",
            // 情绪倾诉：短句但落在领域内，不能被当成寒暄
            "我今天很累,                     EMOTION",
            "最近工作压力好大,               EMOTION",
            // 踩过的坑：含"什么"但没有提问意图，判成知识问答就会跳过共情
            "我今天特别难过，什么都不想说,   EMOTION",
            // 知识型提问：领域词 + 疑问标记
            "我最近总是睡不着，有什么办法吗, KNOWLEDGE",
            "怎么才能睡着,                   KNOWLEDGE",
            "你好，我想问一下怎么缓解焦虑,   KNOWLEDGE",
            // 风险：命中即危机，优先级高于一切
            "我不想活了,         CRISIS",
            "我想死,             CRISIS",
            "我真的撑不下去了,   EMOTION",
            // 领域外：不能因为含"怎么"就去检索（天气问题不属于这个知识库）
            "今天天气怎么样,     EMOTION"
    })
    @DisplayName("路由判定的边界用例")
    void routeBoundaries(String message, AgentRoute expected) {
        assertThat(TriageNode.route(message)).isEqualTo(expected);
    }

    @Test
    @DisplayName("转述他人的风险表述也按危机处理（宁严勿松）")
    void riskInThirdPersonIsStillCrisis() {
        assertThat(TriageNode.route("我朋友说他不想活了")).isEqualTo(AgentRoute.CRISIS);
    }

    @Test
    @DisplayName("中风险表述不升级为危机，避免把耗竭当成自伤")
    void mediumRiskIsNotCrisis() {
        assertThat(TriageNode.route("我撑不住了")).isEqualTo(AgentRoute.EMOTION);
    }

    @Test
    @DisplayName("风险优先于提问意图：带疑问词的自伤表述仍判危机")
    void riskBeatsQuestionIntent() {
        assertThat(TriageNode.route("我不想活了，怎么办")).isEqualTo(AgentRoute.CRISIS);
    }

    @Test
    @DisplayName("apply 写入路由，detail 返回可读标签")
    void applyWritesRouteAndDetail() {
        AgentContext context = new AgentContext(1L, null, "我不想活了", List.of(), "trace-1");

        node.apply(context);

        assertThat(context.getRoute()).isEqualTo(AgentRoute.CRISIS);
        assertThat(node.detail(context)).isEqualTo("危机干预");
    }
}
