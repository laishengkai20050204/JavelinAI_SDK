package com.example.service.impl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class PlanAction {
    private String action;
    private Map<String, Object> args;
}
