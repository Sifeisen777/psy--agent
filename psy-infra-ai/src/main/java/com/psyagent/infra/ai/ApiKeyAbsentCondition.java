package com.psyagent.infra.ai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 条件：没有可用 Key 时装配 Mock 实现，保证项目永远能启动。
 */
public class ApiKeyAbsentCondition implements Condition {

    private final ApiKeyPresentCondition present = new ApiKeyPresentCondition();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !present.matches(context, metadata);
    }
}
