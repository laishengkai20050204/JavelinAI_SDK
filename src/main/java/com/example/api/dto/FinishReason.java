package com.example.api.dto;

public enum FinishReason {
    DONE,        // 真正完成（才转正、才清理）
    WAIT_CLIENT, // 等客户端结果（不转正、不清理）
    ERROR,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == ERROR || this == CANCELLED;
    }
}
