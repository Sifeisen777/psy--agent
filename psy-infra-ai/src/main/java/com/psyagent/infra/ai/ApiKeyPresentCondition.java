package com.psyagent.infra.ai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 条件：只有 psy.ai.api-key 真实可用时才装配真实模型。
 * <p>
 * 为什么不直接用 {@code @ConditionalOnProperty(prefix="psy.ai", name="api-key")}：
 * 该注解在属性值为空字符串时依然判定为匹配，而我们的 yml 里写的是
 * {@code api-key: ${DEEPSEEK_API_KEY:}}，未注入时会得到空串，"匹配"就成了假阳性。
 */
public class ApiKeyPresentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String key = context.getEnvironment().getProperty("psy.ai.api-key");
        return key != null
                && !key.isBlank()
                && !key.startsWith("sk-placeholder")
                && !key.contains("${");
    }
}
