package com.psyagent.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 启动类。
 * <p>
 * {@code scanBasePackages = "com.psyagent"}：多模块下组件分散在不同 jar 中，
 * 默认只扫描启动类所在包，需显式指定根包。
 * <p>
 * {@code @MapperScan}：Mapper 位于 psy-infra-store 模块的 {@code com.psyagent.store.mapper}。
 * <p>
 * 排除 {@link UserDetailsServiceAutoConfiguration}：本项目使用 JWT 认证，
 * 不需要内存用户与自动生成的随机密码。
 */
@SpringBootApplication(
        scanBasePackages = "com.psyagent",
        exclude = UserDetailsServiceAutoConfiguration.class
)
@MapperScan("com.psyagent.store.mapper")
public class PsyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PsyAgentApplication.class, args);
    }
}
