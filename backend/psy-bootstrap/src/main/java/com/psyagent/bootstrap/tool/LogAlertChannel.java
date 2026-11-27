package com.psyagent.bootstrap.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认告警通道：结构化 WARN 日志。
 * <p>
 * 单独一个 logger、单独一行格式，是为了让监控系统能按关键字订阅，
 * 而不是从几万行 INFO 里正则捞。没有 SMTP 凭据时，这一条就是告警本身。
 */
@Component
public class LogAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger("CRISIS_ALERT");

    @Override
    public String name() {
        return "LOG";
    }

    @Override
    public boolean send(AlertMessage message) {
        log.warn("level={} reason={} userId={} conversationId={} traceId={}",
                message.level(), message.reason(), message.userId(),
                message.conversationId(), message.traceId());
        return true;
    }
}
