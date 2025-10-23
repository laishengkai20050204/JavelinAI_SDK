package com.example.service.impl.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class NdjsonEvent {
    private String event;
    private String ts;
    private Map<String, Object> data;
}
