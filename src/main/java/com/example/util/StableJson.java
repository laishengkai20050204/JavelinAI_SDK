package com.example.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;
import java.util.TreeMap;

public final class StableJson {
    private StableJson(){}
    public static String stringify(Map<String,Object> map, ObjectMapper om) throws JsonProcessingException {
        // 简化稳定化：键排序（深度排序可后续增强）
        var sorted = new TreeMap<>(map);
        return om.copy().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).writeValueAsString(sorted);
    }
}
