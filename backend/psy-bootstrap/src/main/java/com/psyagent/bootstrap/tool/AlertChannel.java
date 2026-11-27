package com.psyagent.bootstrap.tool;

/** 告警出站通道。可以并存多个，危机告警会向全部通道广播。 */
public interface AlertChannel {

    String name();

    /** @return 是否成功投递 */
    boolean send(AlertMessage message);

    record AlertMessage(String level, String reason, Long userId,
                        Long conversationId, String traceId) {
    }
}
