import React, { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import {
    Play, Square, Download, Filter, RefreshCw, Languages,
    Clipboard, ClipboardCheck, Binary, MessageSquare, Workflow, Wrench
} from "lucide-react";
import { readNdjson } from "../lib/ndjson"; // 若没有该文件，可用文末“内联版本”代替

/* ===== 事件类型（可选） ===== */
type ReplayEvent =
    | { event: "started"; ts?: string; data?: any }
    | { event: "finished"; ts?: string; data?: any }
    | { event?: string; ts?: string; data: { type: "message" | "decision" | "tool"; [k: string]: any } }
    | any;

/** 回放中心（与 AdminConfigConsole 同风格） */
export default function ReplayCenterPage() {
    // ===== i18n =====
    type Lang = "zh" | "en";
    const [lang, setLang] = useState<Lang>(() => {
        try {
            if (typeof navigator !== "undefined") {
                return navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en";
            }
        } catch {}
        return "zh";
    });

    const i18n = {
        zh: {
            title: "Javelin 回放中心",
            subtitle: "按行解析 NDJSON · 工具/决策/消息可视化",
            form: {
                userId: "用户 ID",
                convId: "会话 ID",
                stepId: "Step ID（可选，回放到该步含之前）",
                limit: "Limit（条数上限）",
                start: "开始回放",
                stop: "停止",
                exportJson: "导出 JSON",
                exportNdjson: "导出 NDJSON",
                filter: "筛选",
                refresh: "清空事件",
            },
            banners: { streaming: "正在流式回放...", stopped: "回放已停止", empty: "暂时没有事件", copied: "已复制" },
            filters: { msg: "消息", dec: "决策", tool: "工具", other: "其它" },
        },
        en: {
            title: "Javelin Replay Center",
            subtitle: "NDJSON line-by-line · visualize tools/decisions/messages",
            form: {
                userId: "User ID", convId: "Conversation ID", stepId: "Step ID (optional, up to & including)",
                limit: "Limit", start: "Start Replay", stop: "Stop",
                exportJson: "Export JSON", exportNdjson: "Export NDJSON",
                filter: "Filter", refresh: "Clear Events",
            },
            banners: { streaming: "Streaming replay...", stopped: "Replay stopped", empty: "No events yet", copied: "Copied" },
            filters: { msg: "Message", dec: "Decision", tool: "Tool", other: "Other" },
        },
    } as const;
    const t = i18n[lang];

    // ===== state =====
    const [userId, setUserId] = useState("u1");
    const [conversationId, setConversationId] = useState("c1");
    const [stepId, setStepId] = useState<string>("");
    const [limit, setLimit] = useState<number>(1000);

    const [events, setEvents] = useState<ReplayEvent[]>([]);
    const [loading, setLoading] = useState(false);
    const [copiedIdx, setCopiedIdx] = useState<number | null>(null);
    const abortRef = useRef<AbortController | null>(null);
    const scrollerRef = useRef<HTMLDivElement | null>(null);

    // filters
    const [showMsg, setShowMsg] = useState(true);
    const [showDec, setShowDec] = useState(true);
    const [showTool, setShowTool] = useState(true);
    const [showOther, setShowOther] = useState(true);

    useEffect(() => () => abortRef.current?.abort(), []);

    // derived
    const filteredEvents = useMemo(() => {
        return events.filter((e) => {
            const typ = e?.data?.type;
            if (typ === "message") return showMsg;
            if (typ === "decision") return showDec;
            if (typ === "tool") return showTool;
            return showOther; // started/finished/unknown
        });
    }, [events, showMsg, showDec, showTool, showOther]);

    useEffect(() => {
        if (scrollerRef.current) scrollerRef.current.scrollTop = scrollerRef.current.scrollHeight;
    }, [filteredEvents, loading]);

    const clearEvents = () => setEvents([]);

    async function startReplay() {
        abortRef.current?.abort();
        const ac = new AbortController();
        abortRef.current = ac;
        setLoading(true);
        setEvents([]);

        // 走 Vite 代理/同源路径，避免 CORS
        const qs = new URLSearchParams({ userId, conversationId, limit: String(limit) });
        if (stepId) qs.set("stepId", stepId);
        const url = `/ai/replay/ndjson?${qs.toString()}`;

        try {
            await readNdjson(url, (obj) => setEvents((prev) => [...prev, obj]), ac.signal);
        } catch {
            // ignore abort/network errors
        } finally {
            setLoading(false);
        }
    }

    function stopReplay() {
        abortRef.current?.abort();
        setLoading(false);
    }

    function exportAsJson() {
        const blob = new Blob(
            [JSON.stringify({ userId, conversationId, stepId: stepId || undefined, count: events.length, events }, null, 2)],
            { type: "application/json" }
        );
        triggerDownload(blob, `replay-${conversationId}${stepId ? "-" + stepId : ""}.json`);
    }

    function exportAsNdjson() {
        const lines = events.map((e) => JSON.stringify(e)).join("\n") + "\n";
        const blob = new Blob([lines], { type: "application/x-ndjson" });
        triggerDownload(blob, `replay-${conversationId}${stepId ? "-" + stepId : ""}.ndjson`);
    }

    const bannerText = loading ? t.banners.streaming : (events.length === 0 ? t.banners.empty : t.banners.stopped);

    // ===== UI =====
    return (
        <div className="min-h-screen w-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
            {/* Header */}
            <header className="sticky top-0 z-10 border-b bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:border-slate-800 dark:bg-slate-900/80 dark:supports-[backdrop-filter]:bg-slate-900/60">
                <div className="mx-auto max-w-6xl px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="h-9 w-9 rounded-2xl bg-gradient-to-tr from-blue-500 to-indigo-500 text-white grid place-items-center shadow-sm">
                            <Binary size={18} />
                        </div>
                        <div>
                            <h1 className="text-lg font-semibold leading-tight">{t.title}</h1>
                            <p className="text-xs text-slate-500 dark:text-slate-400">{t.subtitle}</p>
                        </div>
                    </div>

                    <div className="flex items-center gap-2">
                        <div className="inline-flex items-center rounded-xl border border-slate-300 bg-white p-1 text-sm dark:border-slate-700 dark:bg-slate-800">
                            <button
                                onClick={() => setLang("zh")}
                                className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "zh" ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                aria-pressed={lang === "zh"}
                            >
                                <Languages size={14} /> 中文
                            </button>
                            <button
                                onClick={() => setLang("en")}
                                className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "en" ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                aria-pressed={lang === "en"}
                            >
                                EN
                            </button>
                        </div>
                    </div>
                </div>
            </header>

            {/* Body */}
            <main className="mx-auto max-w-6xl px-4 py-6">
                {/* banner */}
                <div className="mb-4">
                    <Banner icon={<RefreshCw className={loading ? "animate-spin" : ""} size={16} />} text={bannerText} color={loading ? "slate" : "green"} />
                </div>

                {/* Form */}
                <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                            className="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
                    <Section title={lang === "zh" ? "查询条件" : "Query"}>
                        <div className="grid gap-4 md:grid-cols-4">
                            <Field label={t.form.userId}>
                                <input value={userId} onChange={(e) => setUserId(e.target.value)}
                                       className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                            </Field>
                            <Field label={t.form.convId}>
                                <input value={conversationId} onChange={(e) => setConversationId(e.target.value)}
                                       className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                            </Field>
                            <Field label={t.form.stepId}>
                                <input value={stepId} onChange={(e) => setStepId(e.target.value)}
                                       placeholder={lang === "zh" ? "留空=回放到最近 FINAL" : "empty = up to latest FINAL"}
                                       className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"/>
                            </Field>
                            <Field label={t.form.limit}>
                                <input type="number" min={100} max={5000} value={limit} onChange={(e) => setLimit(Number(e.target.value))}
                                       className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                            </Field>
                        </div>
                    </Section>

                    <Section title={lang === "zh" ? "操作" : "Actions"}>
                        <div className="flex flex-wrap items-center gap-2 md:sticky md:bottom-4 md:z-10 md:rounded-2xl md:border md:border-slate-200 md:bg-slate-50/80 md:p-3 md:backdrop-blur md:supports-[backdrop-filter]:bg-slate-50/60 transition-colors dark:md:border-slate-800 dark:md:bg-slate-900/70 dark:md:supports-[backdrop-filter]:bg-slate-900/60">
                            <button onClick={startReplay} disabled={loading}
                                    className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium ${loading ? "bg-slate-300 text-white dark:bg-slate-700" : "bg-blue-600 text-white hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400"}`}>
                                <Play size={16}/>{t.form.start}
                            </button>
                            <button onClick={stopReplay}
                                    className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                <Square size={16}/>{t.form.stop}
                            </button>
                            <button onClick={clearEvents}
                                    className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                <RefreshCw size={16}/>{t.form.refresh}
                            </button>

                            <span className="mx-2 text-slate-400">|</span>
                            <button onClick={exportAsJson} disabled={events.length === 0}
                                    className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                <Download size={16}/>{t.form.exportJson}
                            </button>
                            <button onClick={exportAsNdjson} disabled={events.length === 0}
                                    className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                <Download size={16}/>{t.form.exportNdjson}
                            </button>

                            <span className="mx-2 text-slate-400">|</span>
                            <div className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-800">
                                <Filter size={16}/>
                                <label className="inline-flex items-center gap-1">
                                    <input type="checkbox" className="accent-blue-600 dark:accent-blue-400" checked={showMsg} onChange={(e)=>setShowMsg(e.target.checked)}/>
                                    {t.filters.msg}
                                </label>
                                <label className="inline-flex items-center gap-1">
                                    <input type="checkbox" className="accent-blue-600 dark:accent-blue-400" checked={showDec} onChange={(e)=>setShowDec(e.target.checked)}/>
                                    {t.filters.dec}
                                </label>
                                <label className="inline-flex items-center gap-1">
                                    <input type="checkbox" className="accent-blue-600 dark:accent-blue-400" checked={showTool} onChange={(e)=>setShowTool(e.target.checked)}/>
                                    {t.filters.tool}
                                </label>
                                <label className="inline-flex items-center gap-1">
                                    <input type="checkbox" className="accent-blue-600 dark:accent-blue-400" checked={showOther} onChange={(e)=>setShowOther(e.target.checked)}/>
                                    {t.filters.other}
                                </label>
                            </div>
                        </div>
                    </Section>

                    <Section title={lang === "zh" ? "事件流" : "Event Stream"}>
                        <div ref={scrollerRef} className="border rounded-xl bg-black text-green-100 p-2 h-[460px] overflow-auto text-sm">
                            {filteredEvents.length === 0 ? (
                                <div className="text-slate-400 p-3">{bannerText}</div>
                            ) : (
                                filteredEvents.map((e, idx) => (
                                    <EventRow
                                        key={idx}
                                        e={e}
                                        lang={lang}
                                        onCopy={()=>{
                                            navigator.clipboard.writeText(JSON.stringify(e, null, 2)).then(()=>{
                                                setCopiedIdx(idx);
                                                setTimeout(()=>setCopiedIdx(null), 1200);
                                            });
                                        }}
                                        copied={copiedIdx === idx}
                                    />
                                ))
                            )}
                        </div>
                    </Section>
                </motion.div>
            </main>
        </div>
    );
}

/* ========== UI helpers（复用你原有的视觉语义） ========== */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <section className="py-4">
            <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">{title}</div>
            {children}
        </section>
    );
}
function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <label className="block text-sm">
            <div className="mb-1 text-slate-500 dark:text-slate-400">{label}</div>
            {children}
        </label>
    );
}
function Banner({ icon, text, color }: { icon: React.ReactNode; text: string; color: "slate" | "green" | "red" }) {
    const tone =
        color === "green"
            ? "bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950 dark:text-emerald-200 dark:border-emerald-900"
            : color === "red"
                ? "bg-red-50 text-red-700 border-red-200 dark:bg-red-950 dark:text-red-200 dark:border-red-900"
                : "bg-slate-50 text-slate-700 border-slate-200 dark:bg-slate-900 dark:text-slate-200 dark:border-slate-800";
    return (
        <div className={`flex items-center gap-2 rounded-xl border px-3 py-2 text-sm ${tone}`}>
            {icon}<span>{text}</span>
        </div>
    );
}

/* ===== 子项：只接收必需 props，避免 TS6133/TS2741 ===== */
function EventRow({ e, onCopy, copied, lang }: {
    e: ReplayEvent; onCopy: () => void; copied: boolean; lang: "zh" | "en";
}) {
    const type = (e as any)?.data?.type;
    const ts = (e as any)?.ts || "";
    const icon = type === "message" ? <MessageSquare size={14}/>
        : type === "decision" ? <Workflow size={14}/>
            : type === "tool" ? <Wrench size={14}/>
                : <Binary size={14}/>;
    return (
        <div className="flex items-start gap-2 px-2 py-1 hover:bg-white/5 rounded-lg">
            <div className="mt-0.5">{icon}</div>
            <div className="flex-1">
                <div className="text-[11px] text-slate-400">{ts} · {type || (e as any)?.event}</div>
                <div className="whitespace-pre-wrap leading-relaxed">{formatEventLine(e, lang)}</div>
            </div>
            <button onClick={onCopy} className="ml-2 opacity-80 hover:opacity-100">
                {copied ? <ClipboardCheck size={14}/> : <Clipboard size={14}/>}
            </button>
        </div>
    );
}

/* ===== 文本格式（升级版：把 \n 变成真换行，并美化 tool_calls） ===== */
function formatEventLine(e: ReplayEvent, lang: "zh"|"en") {
    const typ = (e as any)?.data?.type;
    if (typ === "message") {
        const role = (e as any)?.data?.role ?? "assistant";
        const textRaw = (e as any)?.data?.text ?? "";
        const text = toDisplayMultiline(textRaw);
        return `[${role}] ${text}`;
    }
    if (typ === "decision") {
        const calls = (e as any)?.data?.tool_calls || [];
        const header = lang === "zh" ? "🤖 决策工具:" : "🤖 Decide tools:";
        return header + "\n" + prettyToolCalls(calls, lang);
    }
    if (typ === "tool") {
        const name = (e as any)?.data?.name ?? "tool";
        const reused = (e as any)?.data?.reused ? (lang === "zh" ? "复用" : "reused") : (lang === "zh" ? "新执行" : "fresh");
        const exitCode = (e as any)?.data?.data?.exitCode;
        const text = toDisplayMultiline((e as any)?.data?.text);
        return `🛠 ${name} (${reused})` + (exitCode !== undefined ? ` exit=${exitCode}` : "") + (text ? `\n${text}` : "");
    }
    if ((e as any)?.event === "started")  return (lang === "zh" ? "▶ 开始回放" : "▶ Replay started");
    if ((e as any)?.event === "finished") return (lang === "zh" ? "■ 回放结束" : "■ Replay finished");
    return JSON.stringify(e);
}

/* ===== Helpers：漂亮打印决策里的 tool_calls（含反转义与截断） ===== */
function prettyToolCalls(calls: any[], lang: "zh" | "en") {
    return (calls || []).map((c: any, i: number) => {
        const name = c?.function?.name || c?.name || c?.id || "tool";
        const rawArgs = c?.function?.arguments ?? c?.arguments;
        const parsed = deepTryParseJson(rawArgs);          // 把字符串 JSON 解成对象
        const shown  = summarizeArgsForDisplay(parsed);    // 重要字段摘要 + 反转义
        const idxStr = `#${i + 1}`;
        const label  = lang === "zh" ? "参数" : "args";
        return `${idxStr} ${name}\n${label}: ${shown}`;
    }).join("\n\n");
}

/** 把字符串里“字面量 \n / \r\n / \t”转成真实换行与制表符 */
function toDisplayMultiline(v: any): string {
    if (typeof v !== "string") return v ?? "";
    // 尝试用 JSON 反转义一次（对包含 \uXXXX 也有效）
    try {
        const unescaped = JSON.parse(`"${v.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`);
        return unescaped;
    } catch {
        // 兜底：简单替换
        return v.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
    }
}

/** 尝试把字符串 JSON 解到对象；若本身是对象则原样返回 */
function deepTryParseJson(v: any) {
    if (typeof v !== "string") return v;
    try {
        const once = JSON.parse(v);
        if (typeof once === "string") {
            try { return JSON.parse(once); } catch { return toDisplayMultiline(once); }
        }
        return once;
    } catch {
        return v;
    }
}

/** 美化参数：对超长字符串/代码字段做摘要 + 反转义 \n */
function summarizeArgsForDisplay(args: any) {
    const MAX_STR = 160;   // 展示字符串长度上限
    const MAX_LINES = 12;  // 代码最多展示行数

    if (args == null) return "null";
    if (typeof args === "number" || typeof args === "boolean") return String(args);

    // ✅ 关键1：字符串直接返回“解转义后的真实文本”，不要再 JSON.stringify
    if (typeof args === "string") {
        const s0 = toDisplayMultiline(args);
        return s0.length > MAX_STR ? s0.slice(0, MAX_STR) + "…" : s0;
    }

    if (typeof args === "object") {
        const clone: any = Array.isArray(args) ? [...args] : { ...args };

        // 先把所有 string 字段做一次反转义
        for (const k of Object.keys(clone)) {
            const v = clone[k];
            if (typeof v === "string") clone[k] = toDisplayMultiline(v);
        }

        // 针对 code 字段做行数裁剪
        if (clone.code != null) {
            const code  = String(clone.code);
            const lines = code.split(/\r?\n/);
            const head  = lines.slice(0, MAX_LINES).join("\n");
            const more  = lines.length > MAX_LINES ? `\n…(${lines.length - MAX_LINES} more lines)` : "";
            clone.code  = head + more;
        }

        // 对其它很长的字符串裁剪
        for (const k of Object.keys(clone)) {
            if (k === "code") continue;
            const v = clone[k];
            if (typeof v === "string" && v.length > MAX_STR) {
                clone[k] = v.slice(0, MAX_STR) + "…";
            }
        }

        // ✅ 关键2：对象为了排版依然 stringify，但立刻用 toDisplayMultiline 把 \n 还原成真换行
        return toDisplayMultiline(JSON.stringify(clone, null, 2));
    }

    try {
        return toDisplayMultiline(JSON.stringify(args, null, 2));
    } catch {
        return String(args);
    }
}


/* ===================== utils ===================== */
function triggerDownload(blob: Blob, filename: string) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
}

/* ========== 若你没有 ../lib/ndjson，可改用内联版本（去掉上面的 import） ==========
async function readNdjson(url: string, onEvent: (obj: any) => void, signal?: AbortSignal) {
  // 使用相对路径即可通过 Vite 代理 -> 后端，避免 CORS
  const r = await fetch(url, { headers: { "Accept": "application/x-ndjson" }, signal });
  if (!r.body) throw new Error("No body");
  const reader = r.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      try { onEvent(JSON.parse(line)); } catch { /* ignore bad line *-/ }
    }
  }
  const rest = buf.trim();
  if (rest) { try { onEvent(JSON.parse(rest)); } catch {} }
}
*/
