// src/pages/ReplayCenterPage.tsx
import React, { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import {
    Play, Square, Download, Filter, RefreshCw, Languages,
    Clipboard, ClipboardCheck, Binary, MessageSquare, Workflow, Wrench, Copy
} from "lucide-react";
import { readNdjson } from "../lib/ndjson";
import { markdownToSafeHtml } from "../lib/markdown";
import { JsonViewer } from "../components/JsonViewer";
import { TypeBadge } from "../components/TypeBadge";
import { buildReplayCurl } from "../lib/curl";
import { useSharedIds } from "../lib/sharedIds";

/* ===== 类型（与原来一致） ===== */
type ReplayEvent =
    | { event: "started"; ts?: string; data?: any }
    | { event: "finished"; ts?: string; data?: any }
    | { event?: string; ts?: string; data: { type: "message" | "decision" | "tool"; [k: string]: any } }
    | any;

export default function ReplayCenterPage() {
    type Lang = "zh" | "en";
    const [lang, setLang] = useState<Lang>(() => {
        try { if (typeof navigator !== "undefined") return navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en"; } catch {}
        return "zh";
    });

    const i18n = {
        zh: {
            title: "Javelin 回放中心",
            subtitle: "按行解析 NDJSON · 工具/决策/消息可视化",
            form: {
                userId: "用户 ID", convId: "会话 ID", stepId: "Step ID（可选，回放到该步含之前）",
                limit: "Limit（条数上限）", start: "开始回放", stop: "停止",
                exportJson: "导出 JSON", exportNdjson: "导出 NDJSON",
                filter: "筛选", refresh: "清空事件", curl: "复制为 cURL",
                follow: "自动跟随",
            },
            banners: { streaming: "正在流式回放...", stopped: "回放已停止", empty: "暂时没有事件", copied: "已复制" },
            filters: { msg: "消息", dec: "决策", tool: "工具", other: "其它" },
            showArgs: "查看参数",
            showToolOut: "查看工具输出",
        },
        en: {
            title: "Javelin Replay Center",
            subtitle: "NDJSON line-by-line · visualize tools/decisions/messages",
            form: {
                userId: "User ID", convId: "Conversation ID", stepId: "Step ID (optional, up to & including)",
                limit: "Limit", start: "Start Replay", stop: "Stop",
                exportJson: "Export JSON", exportNdjson: "Export NDJSON",
                filter: "Filter", refresh: "Clear Events", curl: "Copy as cURL",
                follow: "Auto follow",
            },
            banners: { streaming: "Streaming replay...", stopped: "Replay stopped", empty: "No events yet", copied: "Copied" },
            filters: { msg: "Message", dec: "Decision", tool: "Tool", other: "Other" },
            showArgs: "Show Args",
            showToolOut: "Show Tool Output",
        },
    } as const;
    const t = i18n[lang];

    // ===== state =====
    const { userId, setUserId, conversationId, setConversationId } = useSharedIds("u1", "c1");
    const [stepId, setStepId] = useState<string>("");
    const [limit, setLimit] = useState<number>(1000);

    const [events, setEvents] = useState<ReplayEvent[]>([]);
    const [loading, setLoading] = useState(false);
    const [copiedIdx, setCopiedIdx] = useState<number | null>(null);
    const [autoFollow, setAutoFollow] = useState(true);

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
        const el = scrollerRef.current;
        if (!el) return;
        const nearBottom = el.scrollHeight - (el.scrollTop + el.clientHeight) < 80;
        if (nearBottom) setAutoFollow(true);
        if (autoFollow) el.scrollTop = el.scrollHeight;
    }, [filteredEvents, loading, autoFollow]);

    const clearEvents = () => setEvents([]);

    async function startReplay() {
        abortRef.current?.abort();
        const ac = new AbortController(); abortRef.current = ac;
        setLoading(true); setEvents([]);

        const qs = new URLSearchParams({ userId, conversationId, limit: String(limit) });
        if (stepId) qs.set("stepId", stepId);
        const url = `/ai/replay/ndjson?${qs.toString()}`;

        try {
            await readNdjson(url, (obj) => setEvents((prev) => [...prev, obj]), ac.signal);
        } catch {} finally {
            setLoading(false);
        }
    }
    function stopReplay() { abortRef.current?.abort(); setLoading(false); }

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
    async function copyCurl() {
        const cmd = buildReplayCurl({ userId, conversationId, stepId, limit });
        await navigator.clipboard.writeText(cmd);
    }

    const bannerText = loading ? t.banners.streaming : (events.length === 0 ? t.banners.empty : t.banners.stopped);

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
                            <button onClick={() => setLang("zh")} className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang==="zh" ? "bg-slate-200 dark:bg-slate-700":""}`} aria-pressed={lang==="zh"}>
                                <Languages size={14}/> 中文
                            </button>
                            <button onClick={() => setLang("en")} className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang==="en" ? "bg-slate-200 dark:bg-slate-700":""}`} aria-pressed={lang==="en"}>
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

                <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                            className="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">

                    {/* Query */}
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
                                       placeholder={lang==="zh" ? "留空=回放到最近 FINAL" : "empty = up to latest FINAL"}
                                       className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"/>
                            </Field>
                            <Field label={t.form.limit}>
                                <input type="number" min={100} max={5000} value={limit} onChange={(e)=>setLimit(Number(e.target.value))}
                                       className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                            </Field>
                        </div>
                    </Section>

                    {/* Actions */}
                    <Section title={lang === "zh" ? "操作" : "Actions"}>
                        <div className="flex flex-wrap items-center gap-2 md:sticky md:bottom-4 md:z-10 md:rounded-2xl md:border md:border-slate-200 md:bg-slate-50/80 md:p-3 md:backdrop-blur md:supports-[backdrop-filter]:bg-slate-50/60 transition-colors dark:md:border-slate-800 dark:md:bg-slate-900/70 dark:md:supports-[backdrop-filter]:bg-slate-900/60">
                            <button onClick={startReplay} disabled={loading}
                                    className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium ${loading?"bg-slate-300 text-white dark:bg-slate-700":"bg-blue-600 text-white hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400"}`}>
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

                            <button onClick={copyCurl}
                                    className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                <Copy size={16}/>{t.form.curl}
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

                                <label className="inline-flex items-center gap-1 ml-2">
                                    <input type="checkbox" className="accent-blue-600 dark:accent-blue-400" checked={autoFollow} onChange={(e)=>setAutoFollow(e.target.checked)}/>
                                    {t.form.follow}
                                </label>
                            </div>
                        </div>
                    </Section>

                    {/* Stream */}
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

/* === 子组件 & 工具（沿用你原有风格） === */
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

/* === 事件行：集成 Markdown / JSON 折叠 / 类型徽章 === */
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
                <div className="flex items-center gap-2 text-[11px] text-slate-400">
                    <span>{ts}</span>
                    <TypeBadge type={type || (e as any)?.event} />
                </div>
                <div className="mt-1">{renderEventContent(e, lang)}</div>
            </div>
            <button onClick={onCopy} className="ml-2 opacity-80 hover:opacity-100">
                {copied ? <ClipboardCheck size={14}/> : <Clipboard size={14}/>}
            </button>
        </div>
    );
}

/* === 内容渲染：消息 -> Markdown；decision/tool -> 摘要 + JSON 折叠 === */
function renderEventContent(e: ReplayEvent, lang: "zh"|"en") {
    const t = (e as any)?.data?.type;

    // 消息：Markdown（带反转义）
    if (t === "message") {
        const role = (e as any)?.data?.role ?? "assistant";
        const textRaw = (e as any)?.data?.text ?? "";

        // ✅ 关键：在 markdown.ts 里已经做了反转义
        const html = markdownToSafeHtml(String(textRaw));

        return (
            <div>
                <div className="mb-1 text-[12px] text-slate-400">{`[${role}]`}</div>
                <div
                    className="prose prose-sm prose-slate max-w-none dark:prose-invert rounded-lg bg-slate-900/60 p-3"
                    dangerouslySetInnerHTML={{ __html: html }}
                />
            </div>
        );
    }

    // 决策：每个 tool_call 展开参数
    if (t === "decision") {
        const calls = (e as any)?.data?.tool_calls || [];
        return (
            <div className="space-y-2">
                <div className="text-sm">{lang==="zh" ? "🤖 决策工具" : "🤖 Decide tools"}</div>
                {calls.map((c: any, i: number) => {
                    const name = c?.function?.name || c?.name || c?.id || "tool";
                    const rawArgs = c?.function?.arguments ?? c?.arguments;
                    const parsed  = tryParseTwice(rawArgs);
                    return (
                        <div key={i} className="rounded-lg border border-slate-700 bg-slate-900/40 p-2">
                            <div className="mb-1 text-[12px] text-slate-300">{`#${i+1} ${name}`}</div>
                            <JsonViewer data={parsed} label={lang==="zh" ? "参数" : "args"} defaultOpen />
                        </div>
                    );
                })}
            </div>
        );
    }

    // 工具：输出文本 + args 折叠
    if (t === "tool") {
        const name = (e as any)?.data?.name ?? "tool";
        const reused = (e as any)?.data?.reused ? (lang === "zh" ? "复用" : "reused") : (lang === "zh" ? "新执行" : "fresh");
        const exitCode = (e as any)?.data?.data?.exitCode;
        const text = (e as any)?.data?.text;
        const argsRaw = (e as any)?.data?.args;
        const argsParsed = tryParseTwice(argsRaw);
        return (
            <div className="space-y-2">
                <div className="text-sm">
                    {`🛠 ${name} (${reused})`}
                    {exitCode !== undefined ? ` · exit=${exitCode}` : ""}
                </div>
                {text != null && (
                    <details className="rounded-lg border border-slate-700 bg-slate-900/40 p-2" open>
                        <summary className="cursor-pointer select-none text-[12px] text-slate-300">
                            {lang==="zh" ? "工具输出" : "Tool Output"}
                        </summary>
                        {typeof text === 'string' || typeof text === 'number' || typeof text === 'boolean' ? (
                            <pre className="mt-2 whitespace-pre-wrap text-xs text-emerald-200">{String(text)}</pre>
                        ) : (
                            <div className="mt-2">
                                <JsonViewer data={text} defaultOpen={false} />
                            </div>
                        )}
                    </details>
                )}
                {argsRaw && (
                    <details className="rounded-lg border border-slate-700 bg-slate-900/40 p-2">
                        <summary className="cursor-pointer select-none text-[12px] text-slate-300">
                            {lang==="zh" ? "调用参数" : "Args"}
                        </summary>
                        <div className="mt-2">
                            <JsonViewer data={argsParsed} defaultOpen={false} />
                        </div>
                    </details>
                )}
            </div>
        );
    }

    if ((e as any)?.event === "started")  return <div> {lang==="zh" ? "▶ 开始回放" : "▶ Replay started"} </div>;
    if ((e as any)?.event === "finished") return <div> {lang==="zh" ? "■ 回放结束" : "■ Replay finished"} </div>;
    return <pre className="whitespace-pre-wrap text-xs">{JSON.stringify(e, null, 2)}</pre>;
}

/* —— 小工具：安全尝试两次 JSON.parse，并处理 \n —— */
function tryParseTwice(v: any) {
    if (typeof v !== "string") return v;
    try {
        const a = JSON.parse(v);
        if (typeof a === "string") {
            try { return JSON.parse(a); } catch { return unescapeText(a); }
        }
        return a;
    } catch { return unescapeText(v); }
}
function unescapeText(s: string) {
    try {
        return JSON.parse(`"${s.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`);
    } catch {
        return s.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
    }
}

/* utils */
function triggerDownload(blob: Blob, filename: string) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
}
