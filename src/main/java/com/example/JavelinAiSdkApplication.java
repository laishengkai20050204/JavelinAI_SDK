package com.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class JavelinAiSdkApplication {

    public static void main(String[] args) {
        log.info("Starting JavelinAI SDK application");
        SpringApplication.run(JavelinAiSdkApplication.class, args);
        log.info("JavelinAI SDK application started");
    }

}
