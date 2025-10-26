package com.example.tools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IdempotentTool {
    boolean enabled() default true;
    int ttlSeconds() default -1;                // -1 用默认配置
    String[] ignoreArgs() default {"timestamp","requestId","nonce"};
}