package com.psyagent.infra.ai;

/**
 * Mock 实现：没有模型 Key 时兜底。
 * <p>
 * 为什么需要它：新人拉下代码第一件事是 {@code mvn spring-boot:run}，
 * 如果因为"没配 Key"直接启动失败，项目就死在这一步了。
 * 有 Mock 之后，CI 也能在无外网环境下跑通全链路集成测试。
 * <p>
 * 它不"假装聪明"，只回显 + 固定文案，避免开发者误以为模型接好了。
 */
public class MockAiChatFacade implements AiChatFacade {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String modelName() {
        return "mock";
    }

    @Override
    public String chat(String userId, String message) {
        return "【Mock 模式】未检测到 DEEPSEEK_API_KEY，当前不会调用真实模型。\n"
                + "你刚才说：「" + message + "」\n"
                + "配置 Key 后即可获得真实回复：在 psy-bootstrap/src/main/resources/application-local.yml 中设置 psy.ai.api-key。";
    }
}
