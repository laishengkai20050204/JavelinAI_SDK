// src/main/java/com/example/config/PythonToolProperties.java
package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "tools.python")
public class PythonToolProperties {
    /** 总开关 */
    private boolean enabled = true;

    /** 本机解释器命令，Windows 常是 python.exe，Linux 常是 python3 */
    private String pythonCmd = "python";

    /** 单次执行默认超时（可被入参 timeout_ms 缩短） */
    private Duration timeout = Duration.ofSeconds(15);

    /** 最大返回输出（stdout/stderr 合并限制，超过将截断） */
    private long maxOutputBytes = 64 * 1024;

    /** 允许 pip 安装依赖（默认禁用，强烈建议仅在隔离环境/Docker 下开启） */
    private boolean allowPip = false;

    /** 使用 Docker 隔离执行（可选） */
    private boolean useDocker = false;

    /** Docker 镜像名（useDocker=true 时有效） */
    private String dockerImage = "python:3.11-slim";

    /** 禁网标志（仅 Docker 模式可可靠生效：--network=none） */
    private boolean denyNetwork = true;
}
