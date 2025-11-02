package com.example;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(basePackages = {
        "com.example.mapper",      // 你原有的
        "com.example.audit"        // 新增：包含 AuditMapper 的包
})
@Slf4j
public class JavelinAiSdkApplication {

    public static void main(String[] args) {
        log.info("Starting JavelinAI SDK application");
        SpringApplication.run(JavelinAiSdkApplication.class, args);
        log.info("JavelinAI SDK application started");
    }

}
