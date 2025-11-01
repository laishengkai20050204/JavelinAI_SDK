package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.WebSearchProperties;
import com.example.tools.AiTool;
import com.example.tools.support.JsonCanonicalizer;
import com.example.util.Fingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import com.example.tools.support.ProxySupport;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * web_search（Serper.dev�?
 * 参数�?
 *  - q (string, 必填)
 *  - type (web|news|images) 默认 web
 *  - top_k (int, 默认 5, 最�?10)
 *  - lang -> hl
 *  - country -> gl
 *  - page (int, 默认 1)
 *  - site (string, 可�? 作为 site:domain 前缀拼入 q
 *  - safe (bool, 默认 true)
 */
@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class WebSearchTool implements AiTool {

    private final ObjectMapper mapper;
    private final WebClient.Builder webClientBuilder;
    private final WebSearchProperties props;

    @PostConstruct
    public void checkConfig() {
        var s = props.getSerper();
        log.info("[web_search] baseUrl={}, timeout={}, apiKey?={}",
                s.getBaseUrl(), s.getTimeout(), !Objects.equals(s.getApiKey(), "${SERPER_API_KEY}"));
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web/news/images using Serper.dev. Use it for fresh facts, news, or web pages.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> propsMap = new LinkedHashMap<>();
        propsMap.put("q", Map.of("type", "string", "description", "query string", "minLength", 1));
        propsMap.put("type", Map.of("type", "string", "enum", List.of("web", "news", "images"), "default", "web"));
        propsMap.put("top_k", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 5));
        propsMap.put("lang", Map.of("type", "string", "description", "language preference (hl)"));
        propsMap.put("country", Map.of("type", "string", "description", "country/region (gl)"));
        propsMap.put("page", Map.of("type", "integer", "minimum", 1, "default", 1));
        propsMap.put("site", Map.of("type", "string", "description", "site limiter, e.g., github.com"));
        propsMap.put("safe", Map.of("type", "boolean", "default", true));

        schema.put("properties", propsMap);
        schema.put("required", List.of("q"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            // === 1) 读取 & 规范化参�?===
            String rawQ = str(args.get("q"));
            if (!StringUtils.hasText(rawQ)) {
                { log.error("[web_search] invalid args: q is missing"); return ToolResult.error(null, name(), "Missing required parameter: q"); }
            }
            String type = enumType(str(args.get("type")), "web");
            int topK = clamp(intOr(args.get("top_k"), props.getDefaults().getTopK()), 1, 10);
            String lang = def(str(args.get("lang")), props.getDefaults().getLang());
            String country = def(str(args.get("country")), props.getDefaults().getCountry());
            int page = Math.max(1, intOr(args.get("page"), 1));
            String site = sanitizeSite(str(args.get("site")));
            boolean safe = boolOr(args.get("safe"), props.getDefaults().isSafe());

            String q = buildQuery(rawQ, site);

            // === 2) 指纹（与去重框架对齐�?===
            Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("q", q.toLowerCase().trim());
            fp.put("type", type);
            fp.put("top_k", topK);
            fp.put("lang", lowerOrNull(lang));
            fp.put("country", lowerOrNull(country));
            fp.put("page", page);
            fp.put("safe", safe);
            if (StringUtils.hasText(site)) fp.put("site", site.toLowerCase());

            String canonical = JsonCanonicalizer.canonicalize(
                    mapper,
                    mapper.valueToTree(fp),
                    java.util.Collections.emptySet()   // �?java.util.Set.of()
            );
            String fingerprint = Fingerprint.sha256(canonical);
            String executedKey = name() + "::" + fingerprint;

            // === 3) 准备 HTTP 客户�?===
            WebClient.Builder builder = webClientBuilder
                    .clone()
                    .baseUrl(props.getSerper().getBaseUrl())
                    .defaultHeader("X-API-KEY", Objects.toString(props.getSerper().getApiKey(), ""));
            builder = ProxySupport.configureWebClientProxyFromEnv(builder, "web_search");
            WebClient client = builder.build();

            Duration timeout = props.getSerper().getTimeout();

            // === 4) 选择端点 & 请求体映�?===
            String endpoint = switch (type) {
                case "news" -> "/news";
                case "images" -> "/images";
                default -> "/search";
            };

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", q);
            body.put("num", topK);
            if (StringUtils.hasText(lang)) body.put("hl", lang);
            if (StringUtils.hasText(country)) body.put("gl", country);
            if (page > 1) body.put("page", page);
            // safe 可按需映射，Serper 若无对应可忽略（保留在你的层面过�?提示�?

            // === 5) 发起调用 ===
            JsonNode json = client.post()
                    .uri(URI.create(props.getSerper().getBaseUrl() + endpoint))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            if (json == null) {
                { log.error("[web_search] empty response from serper"); return ToolResult.error(null, name(), "Empty response from Serper"); }
            }

            // === 6) 解析响应，标准化�?payload ===
            List<Map<String, Object>> payload;
            switch (type) {
                case "news" -> payload = toNewsPayload(json);
                case "images" -> payload = toImagePayload(json);
                default -> payload = toWebPayload(json);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payload", payload);
            data.put("_executedKey", executedKey);
            data.put("_provider", "serper");
            data.put("_endpoint", endpoint);
            data.put("_q", rawQ);

            return ToolResult.success(null, name(), false, data);
        } catch (WebClientResponseException wex) {
            String msg = "HTTP " + wex.getRawStatusCode() + " " + wex.getStatusText() + ": " + safeTrim(wex.getResponseBodyAsString());
            log.error("[web_search] Serper error: {}", msg);
            return ToolResult.error(null, name(), msg);
        } catch (Exception e) {
            log.error("[web_search] Exception", e);
            return ToolResult.error(null, name(), e.getMessage());
        }
    }

    // ===================== 辅助方法 =====================

    private static String buildQuery(String q, String site) {
        if (StringUtils.hasText(site)) {
            return "site:" + site + " " + q;
        }
        return q;
    }

    private static String sanitizeSite(String s) {
        if (!StringUtils.hasText(s)) return null;
        String x = s.trim().toLowerCase();
        x = x.replaceFirst("^https?://", "");
        x = x.replaceAll("/.*$", ""); // 只保留域�?
        return x;
    }

    private static String enumType(String v, String def) {
        if (!StringUtils.hasText(v)) return def;
        String t = v.toLowerCase();
        return switch (t) {
            case "web", "news", "images" -> t;
            default -> def;
        };
    }

    private static String def(String v, String d) { return StringUtils.hasText(v) ? v : d; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int intOr(Object o, int d) { try { return o == null ? d : Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return d; } }
    private static boolean boolOr(Object o, boolean d) { return o == null ? d : ("true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String lowerOrNull(String v) { return StringUtils.hasText(v) ? v.toLowerCase() : null; }
    private static String safeTrim(String s) { return s == null ? null : s.trim(); }

    private List<Map<String, Object>> toWebPayload(JsonNode root) {
        JsonNode arr = root.path("organic");
        if (arr == null || !arr.isArray()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = text(n, "title");
            String url = text(n, "link");
            String snippet = prefer(n, List.of("snippet", "snippetHighlighted", "description"));
            if (StringUtils.hasText(url) && StringUtils.hasText(title)) {
                list.add(Map.of(
                        "title", title,
                        "url", url,
                        "snippet", Optional.ofNullable(snippet).orElse(""),
                        "source", "serper"
                ));
            }
        }
        return limit(list, 10);
    }

    private List<Map<String, Object>> toNewsPayload(JsonNode root) {
        JsonNode arr = root.path("news");
        if (arr == null || !arr.isArray()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = text(n, "title");
            String url = text(n, "link");
            String snippet = prefer(n, List.of("snippet", "snippetHighlighted", "description"));
            String publishedAt = text(n, "date"); // 不同账号字段名可能不同，先兜�?
            if (StringUtils.hasText(url) && StringUtils.hasText(title)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", title);
                item.put("url", url);
                item.put("snippet", Optional.ofNullable(snippet).orElse(""));
                item.put("source", "serper");
                if (StringUtils.hasText(publishedAt)) item.put("publishedAt", publishedAt);
                list.add(item);
            }
        }
        return limit(list, 10);
    }

    private List<Map<String, Object>> toImagePayload(JsonNode root) {
        JsonNode arr = root.path("images");
        if (arr == null || !arr.isArray()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = text(n, "title");
            String url = text(n, "imageUrl"); // 常见字段名，若不同请按你的实际响应调�?
            String thumb = text(n, "thumbnailUrl");
            if (StringUtils.hasText(url)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", Optional.ofNullable(title).orElse(""));
                item.put("url", url);
                if (StringUtils.hasText(thumb)) item.put("thumbnail", thumb);
                item.put("snippet", "");
                item.put("source", "serper");
                list.add(item);
            }
        }
        return limit(list, 10);
    }

    private static List<Map<String, Object>> limit(List<Map<String, Object>> list, int max) {
        if (list.size() <= max) return list;
        return list.subList(0, max);
    }

    private static String text(JsonNode n, String field) {
        JsonNode x = n.get(field);
        return (x != null && !x.isNull()) ? x.asText() : null;
    }

    private static String prefer(JsonNode n, List<String> fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }
}
