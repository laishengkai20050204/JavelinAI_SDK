// src/main/java/com/example/config/PythonToolProperties.java
package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "ai.tools.python")
public class PythonToolProperties {
    /** 开关 */
    private boolean enabled = true;

    /** 本机解释器命令（Windows 常为 python.exe，Linux 常为 python3） */
    private String pythonCmd = "python3";

    /** 单次执行最大超时（execute 入参可缩短，但不能超过这里） */
    private Duration timeout = Duration.ofSeconds(15);

    /** stdout/stderr 合并后的最大返回字节数（超过截断） */
    private long maxOutputBytes = 64 * 1024;

    /** 是否允许 pip 安装（强烈建议仅在 Docker 隔离下开启） */
    private boolean allowPip = false;

    /** 是否用 Docker 隔离执行 */
    private boolean useDocker = false;

    /** Docker 镜像名（useDocker=true 时有效） */
    private String dockerImage = "python:3.11-slim";

    /** Docker 禁网（--network=none） */
    private boolean denyNetwork = true;
}
