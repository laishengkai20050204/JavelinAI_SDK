package com.example.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/** 运行期可热更的关键配置（按需扩展字段） */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeConfig {
    /** OPENAI / OLLAMA （字符串更好做 JSON 兼容） */
    private String compatibility;

    /** 优先生效的模型名；为空则回退到 AiProperties 的 model */
    private String model;

    /** 工具循环上限（默认 2；用于 Guardrails） */
    @Builder.Default
    private Integer toolsMaxLoops = 10;

    /** （可选）工具开关：key=工具名，value=true 启用 */
    @Builder.Default
    private Map<String, Boolean> toolToggles = Map.of();

    /** （可选）以后扩展：baseUrl/apiKey/timeout 并配合 Reloadable 重建客户端 */
    private String baseUrl;
    private String apiKey;
    private Long clientTimeoutMs;
    private Long streamTimeoutMs;
}
