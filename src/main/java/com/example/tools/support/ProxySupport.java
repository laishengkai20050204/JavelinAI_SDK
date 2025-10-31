package com.example.tools.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

@Slf4j
public final class ProxySupport {

    private ProxySupport() {}

    public static WebClient.Builder configureWebClientProxyFromEnv(WebClient.Builder builder, String tag) {
        ProxySpec spec = readProxyFromEnv();
        if (spec == null) {
            log.info("[proxy:{}] disabled (no HTTP(S)_PROXY env detected)", tag);
            return builder;
        }
        HttpClient http = HttpClient.create().proxy(p -> {
            ProxyProvider.Builder pb = p.type(spec.type()).host(spec.host()).port(spec.port());
            if (StringUtils.hasText(spec.username())) {
                pb.username(spec.username());
                if (StringUtils.hasText(spec.password())) {
                    pb.password(s -> spec.password());
                }
            }
        });
        log.info("[proxy:{}] enabled via env: {}://{}:{}", tag, scheme(spec.type()), spec.host(), spec.port());
        return builder.clientConnector(new ReactorClientHttpConnector(http));
    }

    private static String scheme(ProxyProvider.Proxy type) {
        return switch (type) {
            case HTTP -> "http";
            case SOCKS4 -> "socks4";
            case SOCKS5 -> "socks5";
        };
    }

    private static ProxySpec readProxyFromEnv() {
        try {
            String raw = Optional.ofNullable(System.getenv("HTTPS_PROXY"))
                    .orElse(Optional.ofNullable(System.getenv("HTTP_PROXY")).orElse(null));
            if (!StringUtils.hasText(raw)) return null;
            URI u = URI.create(raw.trim());
            String scheme = Optional.ofNullable(u.getScheme()).orElse("http").toLowerCase(Locale.ROOT);
            String host = u.getHost();
            int port = u.getPort();
            if (!StringUtils.hasText(host) || port <= 0) return null;
            String userInfo = u.getUserInfo();
            String username = null, password = null;
            if (StringUtils.hasText(userInfo)) {
                int idx = userInfo.indexOf(':');
                if (idx >= 0) { username = userInfo.substring(0, idx); password = userInfo.substring(idx + 1); }
                else username = userInfo;
            }
            ProxyProvider.Proxy type = ProxyProvider.Proxy.HTTP;
            if (scheme.startsWith("socks5")) type = ProxyProvider.Proxy.SOCKS5;
            else if (scheme.startsWith("socks4")) type = ProxyProvider.Proxy.SOCKS4;
            return new ProxySpec(type, host, port, username, password);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private record ProxySpec(ProxyProvider.Proxy type, String host, int port, String username, String password) {}
}