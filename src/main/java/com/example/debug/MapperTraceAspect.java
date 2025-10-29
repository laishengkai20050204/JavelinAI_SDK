 package com.example.debug;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Configuration;
import java.util.Arrays;
import java.util.stream.Collectors;

//@Aspect
//@Configuration
@Slf4j
public class MapperTraceAspect {

    @Around("execution(* com.example.mapper.ToolExecutionMapper.upsertSuccess(..))")
    public Object traceUpsertSuccess(ProceedingJoinPoint pjp) throws Throwable {
        String args = Arrays.stream(pjp.getArgs())
                .map(String::valueOf).collect(Collectors.joining(", "));
        String stack = Arrays.stream(new Throwable().getStackTrace())
                .skip(1).limit(20)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n    "));
        log.warn("[TRACE] ToolExecutionMapper.upsertSuccess args=[{}]\n    {}", args, stack);
        return pjp.proceed();
    }
}
