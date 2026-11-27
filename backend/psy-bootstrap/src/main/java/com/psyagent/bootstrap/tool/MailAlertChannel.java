package com.psyagent.bootstrap.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * 可选的邮件告警通道。
 * <p>
 * 只在显式开启且配置了收件人时装配：没有 SMTP 凭据的本地环境不会因此启动失败，
 * 也不会在每次危机时刷一堆连接超时。告警本来就应该是"多通道广播、单通道失败不影响其余"。
 */
@Component
@ConditionalOnProperty(name = "psy.alert.mail.enabled", havingValue = "true")
public class MailAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(MailAlertChannel.class);

    private final String to;
    private final String from;
    private final String host;
    private final int port;

    public MailAlertChannel(@Value("${psy.alert.mail.to:}") String to,
                            @Value("${psy.alert.mail.from:psy-agent@localhost}") String from,
                            @Value("${psy.alert.mail.host:localhost}") String host,
                            @Value("${psy.alert.mail.port:25}") int port) {
        this.to = to;
        this.from = from;
        this.host = host;
        this.port = port;
    }

    @Override
    public String name() {
        return "MAIL";
    }

    @Override
    public boolean send(AlertMessage message) {
        if (to == null || to.isBlank()) {
            log.warn("mail alert enabled but recipient is empty, skipped traceId={}", message.traceId());
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);

            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(to);
            mail.setSubject("[psy-agent] 危机告警 " + message.level());
            mail.setText("""
                    风险等级：%s
                    触发原因：%s
                    用户 ID：%s
                    会话 ID：%s
                    链路 ID：%s

                    请及时在后台复核该会话。本邮件不包含对话原文。
                    """.formatted(message.level(), message.reason(), message.userId(),
                    message.conversationId(), message.traceId()));
            sender.send(mail);
            return true;
        } catch (RuntimeException e) {
            // SMTP 不可达不能让告警工具整体失败：LOG 通道已经落了一条可检索的记录
            log.warn("mail alert delivery failed traceId={}", message.traceId(), e);
            return false;
        }
    }
}
