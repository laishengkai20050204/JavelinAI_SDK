// src/lib/ndjson.ts

/** 读取 NDJSON 流：逐行解析并回调 onEvent */
export async function readNdjson(
    url: string,
    onEvent: (obj: any) => void,
    signal?: AbortSignal
) {
    // ✅ 强制同源：传了绝对 URL 也剥掉 origin，只保留 path+query
    if (/^https?:\/\//i.test(url)) {
        try {
            const u = new URL(url);
            url = u.pathname + u.search + u.hash; // 例如 -> /ai/replay/ndjson?...
        } catch {
            // ignore parse errors
        }
    }

    const res = await fetch(url, {
        headers: { Accept: 'application/x-ndjson' },
        signal,
    });

    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${await safeText(res)}`);
    }

    if (!res.body) throw new Error('No body');

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';

    for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });

        let idx: number;
        // 逐行分割
        while ((idx = buf.indexOf('\n')) >= 0) {
            const line = buf.slice(0, idx).trim();
            buf = buf.slice(idx + 1);
            if (!line) continue;
            try {
                onEvent(JSON.parse(line));
            } catch {
                // 忽略坏行
            }
        }
    }

    // 尾巴还有一行
    if (buf.trim()) {
        try {
            onEvent(JSON.parse(buf.trim()));
        } catch {
            // ignore
        }
    }
}

async function safeText(res: Response) {
    try {
        return await res.text();
    } catch {
        return '';
    }
}
