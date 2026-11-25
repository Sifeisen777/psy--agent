package com.psyagent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 * <p>
 * {@code scanBasePackages = "com.psyagent"} 是必要的：多模块下 @Component 分散在不同 jar 里，
 * 默认只扫启动类所在包（com.psyagent.bootstrap），psy-infra-ai 里的 Bean 就装配不上。
 */
@SpringBootApplication(scanBasePackages = "com.psyagent")
public class PsyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PsyAgentApplication.class, args);
    }
}
