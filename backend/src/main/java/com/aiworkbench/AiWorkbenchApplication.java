package com.aiworkbench;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@MapperScan("com.aiworkbench.mapper")
@ConfigurationPropertiesScan("com.aiworkbench.config")
@EnableMethodSecurity
@EnableAsync
@EnableScheduling
public class AiWorkbenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiWorkbenchApplication.class, args);
    }
}
