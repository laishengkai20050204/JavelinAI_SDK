// src/lib/curl.ts
export function buildReplayCurl(params: {
    origin?: string; // 默认 window.location.origin
    userId: string;
    conversationId: string;
    stepId?: string;
    limit?: number;
}) {
    const origin = params.origin || (typeof window !== "undefined" ? window.location.origin : "");
    const url = new URL("/ai/replay/ndjson", origin);
    url.searchParams.set("userId", params.userId);
    url.searchParams.set("conversationId", params.conversationId);
    if (params.stepId) url.searchParams.set("stepId", params.stepId);
    if (params.limit != null) url.searchParams.set("limit", String(params.limit));

    // -N 保持行缓冲，持续输出
    return `curl -N -H 'Accept: application/x-ndjson' '${url.toString()}'`;
}
