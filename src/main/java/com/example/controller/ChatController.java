package com.example.controller;

import com.example.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    private final AiService aiService;

    // GET /ai/chat?q=你好
    @Operation(summary = "对话接口", description = "根据提示词返回回复")
    @Parameters({
            @Parameter(name = "q", description = "用户问题", required = true)
    })
    @GetMapping("/chat")
    public String chat(@RequestParam("q") String q) {
        return aiService.chatOnce(q);
    }

}
