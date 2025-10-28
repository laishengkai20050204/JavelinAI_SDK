package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.StepState;
import com.example.service.ContextAssembler;
import com.example.util.Fingerprint;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ContextAssemblerImpl implements ContextAssembler {
    @Override
    public Mono<AssembledContext> assemble(StepState st) {
        // 占位：取请求的 loop 作为上下文摘要
        String base = st.req().q() == null ? "" : st.req().q().trim();
        String hash = Fingerprint.sha256(base);
        return Mono.just(new AssembledContext(List.of(
        ), hash));
    }
}
