// src/pages/StepOrchestratorPage.tsx
import React from "react";
import {
    Play, Square, Send, RefreshCcw, ListChecks, MessagesSquare, AlertTriangle,
    CheckCircle2, Languages, Rocket, Plus, Trash2, Wrench
} from "lucide-react";
import { useSharedIds } from "../lib/sharedIds";

/**
 * StepOrchestratorPage (M3)
 * - NDJSON 调用 /ai/v3/chat/step/ndjson
 * - 等待 CLIENT_WAIT 时，自动收集 clientCalls 并可填写 clientResults
 * - 支持定义 clientTools（函数名/描述/参数 JSON）
 */

export type StepEvent = {
    type: string;                   // STARTED | STEP | FINISHED | ERROR | ...
    ts?: string | number;
    message?: string;
    state?: any;
    transition?: any;
    pendingClientCalls?: ClientCall[];
    [k: string]: any;
};

export type ClientCall = {
    callId: string;                 // ← 由后端的 data.calls[*].id 映射
    name: string;
    args?: any;                     // ← 由后端的 data.calls[*].arguments 映射
    hint?: string;                  // 可放 execTarget 等
};

export type ClientResult = {
    callId: string;
    name: string;
    status: "SUCCESS" | "ERROR";
    reused?: boolean;
    arguments?: any;                // ← 新增：回传原始参数（样例中有）
    data?: any;                     // ← e.g. { payload:{ type:"text", value:"..." } }
    error?: string;                 // 本地校验错误提示
};

// —— clientTools 草稿在前端的形状
type ClientToolDraft = {
    name: string;
    description: string;
    execTarget: "client" | "" | "server";
    paramsText: string; // JSON 文本
    valid: boolean;
    error?: string;
};

/** 将 NDJSON 行（后端的 {event, ts, data}）归一化为页面使用的 StepEvent
 *  1) event: "step" && data.type === "clientCalls"  → 组装 pendingClientCalls
 *  2) event: "step" && data.phase === "CLIENT_WAIT" → 只是状态；用来更新 stepId
 *  3) 其他事件保留基础字段
 */
function normalizeEvent(raw: any): StepEvent {
    if (!raw || typeof raw !== "object") return raw as StepEvent;
    const ev = String(raw.event || "").toUpperCase();
    const d = raw.data || {};

    // 默认骨架
    const base: StepEvent = {
        type: ev === "STEP" ? (d.type ? String(d.type).toUpperCase() : "STEP") : ev,
        ts: raw.ts,
        state: d.stepId ? { stepId: d.stepId, loop: d.loop, phase: d.phase, role: d.type } : undefined,
        message: d.text,
    };

    // clientCalls → pendingClientCalls
    if (ev === "STEP" && d.type === "clientCalls" && Array.isArray(d.calls)) {
        base.pendingClientCalls = d.calls.map((c: any) => ({
            callId: c.id,
            name: c.name,
            args: c.arguments,
            hint: c.execTarget ? `execTarget: ${c.execTarget}` : undefined,
        }));
    }
    return base;
}

export default function StepOrchestratorPage() {
    const [lang, setLang] = React.useState<"zh" | "en">(() =>
        typeof navigator !== "undefined" && navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en"
    );
    const t = I18N[lang];

    const { userId, setUserId, conversationId, setConversationId } = useSharedIds("", "");
    const [prompt, setPrompt] = React.useState("");

    const [startKick, setStartKick] = React.useState(0);
    const prevLenRef = React.useRef(0);

    const [aggEvents, setAggEvents] = React.useState<StepEvent[]>([]);
    const [lastStepId, setLastStepId] = React.useState<string | null>(null);
    const [pendingCalls, setPendingCalls] = React.useState<ClientCall[] | null>(null);

    // —— clientTools 草稿
    const [tools, setTools] = React.useState<ClientToolDraft[]>([]);

    // —— pending 结果草稿（包含 arguments / data / reused）
    const [draftResults, setDraftResults] = React.useState<ClientResult[]>([]);

    const canSendBasic = userId.trim().length > 0 && conversationId.trim().length > 0 && prompt.trim().length > 0;
    const hasInvalidTool = tools.some((x) => !x.valid);
    const canSend = canSendBasic && !hasInvalidTool;

    const [reqBody, setReqBody] = React.useState<any | null>(null);

    const { events, loading, error, cancel } = useNdjson(
        "/ai/v3/chat/step/ndjson",
        reqBody ? { method: "POST", body: reqBody, headers: { "X-Run": String(startKick) } } : undefined
    );

    // —— 合并事件、捕获 stepId 与 clientCalls
    React.useEffect(() => {
        const delta = events.slice(prevLenRef.current);
        if (delta.length === 0) return;
        prevLenRef.current = events.length;

        const normalized = delta.map(normalizeEvent);
        setAggEvents((prev) => [...prev, ...normalized]);

        // 最近的 stepId
        for (let i = normalized.length - 1; i >= 0; i--) {
            const sid = normalized[i]?.state?.stepId ?? normalized[i]?.state?.id ?? null;
            if (sid) { setLastStepId(sid); break; }
        }

        // 最近一批 pendingClientCalls
        const lastPending = [...normalized].reverse()
            .find((e) => Array.isArray(e.pendingClientCalls) && e.pendingClientCalls.length > 0);
        if (lastPending) {
            const pc = lastPending.pendingClientCalls!;
            setPendingCalls(pc);

            // 初始化草稿（带 arguments / data / reused）
            setDraftResults(pc.map((c) => ({
                callId: c.callId,
                name: c.name,
                status: "SUCCESS",
                reused: false,
                arguments: c.args ?? {},
                data: { payload: { type: "text", value: "" } },
            })));
        }
    }, [events]);

    // —— 工具表单
    function addTool(prefill?: "open_url") {
        if (prefill === "open_url") {
            setTools((prev) => [
                ...prev,
                {
                    name: "open_url",
                    description: lang === "zh" ? "在浏览器中打开指定链接" : "Open a URL in the browser",
                    execTarget: "client",
                    paramsText: JSON.stringify(
                        {
                            type: "object",
                            properties: { url: { type: "string", format: "uri" } },
                            required: ["url"],
                        },
                        null,
                        2
                    ),
                    valid: true,
                },
            ]);
            return;
        }
        setTools((prev) => [
            ...prev,
            { name: "", description: "", execTarget: "client", paramsText: "{}", valid: true },
        ]);
    }
    function removeTool(i: number) { setTools((prev) => prev.filter((_, idx) => idx !== i)); }
    function updateTool(i: number, patch: Partial<ClientToolDraft>) {
        setTools((prev) => prev.map((t, idx) => (idx === i ? { ...t, ...patch } : t)));
    }
    function onParamsChange(i: number, text: string) {
        let valid = true; let error: string | undefined;
        try { if (text.trim()) JSON.parse(text); } catch (e: any) { valid = false; error = e?.message || "Invalid JSON"; }
        updateTool(i, { paramsText: text, valid, error });
    }
    function buildClientToolsPayload() {
        return tools
            .filter((t) => t.name.trim().length > 0 && t.valid)
            .map((t) => {
                let params: any = {};
                try { params = t.paramsText.trim() ? JSON.parse(t.paramsText) : { type: "object", properties: {} }; }
                catch { params = { type: "object", properties: {} }; }
                const fn: any = { name: t.name.trim() };
                if (t.description.trim()) fn.description = t.description.trim();
                fn.parameters = params;
                if (t.execTarget) fn["x-execTarget"] = t.execTarget;
                return { type: "function", function: fn };
            });
    }

    // —— 开始
    function start() {
        if (!canSend) return;
        setAggEvents([]); setPendingCalls(null); setLastStepId(null);
        prevLenRef.current = 0;

        const body = {
            userId, conversationId, q: prompt,
            toolChoice: "auto",
            responseMode: "step-json-ndjson",
            clientTools: buildClientToolsPayload(),
        };
        setReqBody(body);
        setStartKick((k) => k + 1);
    }

    // —— 继续（把 clientResults + clientTools 一并带上，符合你的样例）
    function continueWith(results: ClientResult[]) {
        if (!lastStepId) return;
        prevLenRef.current = 0;

        const body = {
            userId, conversationId,
            resumeStepId: lastStepId,
            toolChoice: "auto",
            responseMode: "step-json-ndjson",
            clientTools: buildClientToolsPayload(),
            clientResults: results.map((r) => ({
                callId: r.callId,
                name: r.name,
                status: r.status,
                reused: !!r.reused,
                arguments: r.arguments ?? {},
                data: r.data ?? {},
            })),
        };
        setReqBody(body);
        setStartKick((k) => k + 1);
    }

    // —— 编辑 pending 结果
    function updateDraftResult(i: number, patch: Partial<ClientResult>) {
        setDraftResults((prev) => prev.map((x, idx) => (idx === i ? { ...x, ...patch } : x)));
    }
    const canResume =
        !!lastStepId &&
        Array.isArray(pendingCalls) && pendingCalls.length > 0 &&
        draftResults.length === pendingCalls.length &&
        draftResults.every((r) => !!r.callId && !!r.name && !!r.status);

    return (
        <div className="min-h-screen w-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
            {/* Header */}
            <header className="sticky top-0 z-10 border-b bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:border-slate-800 dark:bg-slate-900/80 dark:supports-[backdrop-filter]:bg-slate-900/60">
                <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
                    <div className="flex items-center gap-3">
                        <div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-tr from-indigo-500 to-fuchsia-500 text-white shadow-sm">
                            <Rocket size={18} />
                        </div>
                        <div>
                            <h1 className="text-lg font-semibold leading-tight">{t.title}</h1>
                            <p className="text-xs text-slate-500 dark:text-slate-400">{t.subtitle}</p>
                        </div>
                    </div>
                    <div className="inline-flex items-center rounded-xl border border-slate-300 bg-white p-1 text-sm dark:border-slate-700 dark:bg-slate-800">
                        <button onClick={() => setLang("zh")}
                                className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "zh" ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                aria-pressed={lang === "zh"}><Languages size={14} /> 中文</button>
                        <button onClick={() => setLang("en")}
                                className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "en" ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                aria-pressed={lang === "en"}>EN</button>
                    </div>
                </div>
            </header>

            {/* Body */}
            <main className="mx-auto max-w-6xl px-4 py-6">
                {/* Banner */}
                <div className="mb-4 space-y-2">
                    {loading && <Banner icon={<RefreshCcw className="animate-spin" size={16} />} text={t.running} color="slate" />}
                    {error && <Banner icon={<AlertTriangle size={16} />} text={error} color="red" />}
                </div>

                {/* 基本输入 */}
                <section className="mb-4 grid gap-4 md:grid-cols-2">
                    <Field label={t.userId}>
                        <input value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="u1"
                               className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                    </Field>
                    <Field label={t.conversationId}>
                        <input value={conversationId} onChange={(e) => setConversationId(e.target.value)} placeholder="c2"
                               className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                    </Field>
                </section>

                {/* 用户输入 */}
                <section className="mb-4">
                    <Field label={t.prompt}>
            <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} rows={4} placeholder={t.promptPh}
                      className="w-full rounded-xl border border-slate-300 bg-white p-3 font-mono text-sm text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                    </Field>
                </section>

                {/* clientTools 定义 */}
                <section className="mb-6 rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
                    <div className="mb-3 flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
                        <Wrench className="h-4 w-4" /> {t.tools.title}
                    </div>
                    <div className="mb-3 flex gap-2">
                        <button onClick={() => addTool()}
                                className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800">
                            <Plus className="h-4 w-4" /> {t.tools.add}
                        </button>
                        <button onClick={() => addTool("open_url")}
                                className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800">
                            <Plus className="h-4 w-4" /> {t.tools.quickOpenUrl}
                        </button>
                    </div>

                    {tools.length === 0 ? (
                        <div className="px-2 py-6 text-sm opacity-70">{t.tools.empty}</div>
                    ) : (
                        <ul className="space-y-3">
                            {tools.map((tool, i) => (
                                <li key={i} className="rounded-xl border p-3 dark:border-slate-700">
                                    <div className="flex items-center justify-between gap-3">
                                        <div className="text-sm font-medium">{t.tools.item} #{i + 1}</div>
                                        <button onClick={() => removeTool(i)}
                                                className="inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-xs hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800">
                                            <Trash2 className="h-3.5 w-3.5" /> {t.tools.remove}
                                        </button>
                                    </div>
                                    <div className="mt-2 grid gap-2 md:grid-cols-5">
                                        <label className="block text-sm md:col-span-1">
                                            <div className="mb-1 text-slate-500 dark:text-slate-400">{t.tools.name}</div>
                                            <input value={tool.name} onChange={(e) => updateTool(i, { name: e.target.value })}
                                                   placeholder="open_url" className="w-full rounded-xl border p-2 text-sm dark:border-slate-700 dark:bg-slate-800"/>
                                        </label>
                                        <label className="block text-sm md:col-span-2">
                                            <div className="mb-1 text-slate-500 dark:text-slate-400">{t.tools.desc}</div>
                                            <input value={tool.description} onChange={(e) => updateTool(i, { description: e.target.value })}
                                                   placeholder={lang === "zh" ? "在浏览器中打开指定链接" : "Open a URL in the browser"}
                                                   className="w-full rounded-xl border p-2 text-sm dark:border-slate-700 dark:bg-slate-800"/>
                                        </label>
                                        <label className="block text-sm md:col-span-2">
                                            <div className="mb-1 text-slate-500 dark:text-slate-400">x-execTarget</div>
                                            <select value={tool.execTarget} onChange={(e) => updateTool(i, { execTarget: e.target.value as any })}
                                                    className="w-full rounded-xl border p-2 text-sm dark:border-slate-700 dark:bg-slate-800">
                                                <option value="client">client</option>
                                                <option value="">(unset)</option>
                                                <option value="server">server</option>
                                            </select>
                                        </label>
                                    </div>
                                    <label className="mt-2 block text-sm">
                                        <div className="mb-1 text-slate-500 dark:text-slate-400">{t.tools.params}</div>
                                        <textarea rows={8} value={tool.paramsText} onChange={(e) => onParamsChange(i, e.target.value)}
                                                  className={`w-full rounded-xl border p-2 font-mono text-xs dark:border-slate-700 dark:bg-slate-800 ${tool.valid ? "" : "border-red-500/70"}`}/>
                                        {!tool.valid && <div className="mt-1 text-xs text-red-600 dark:text-red-300">{tool.error}</div>}
                                        <div className="mt-1 text-xs opacity-70">{t.tools.paramsHint}</div>
                                    </label>
                                </li>
                            ))}
                        </ul>
                    )}
                </section>

                {/* 控制按钮 */}
                <div className="mt-3 flex gap-2">
                    <button onClick={start} disabled={!canSend || loading}
                            className="inline-flex items-center gap-2 rounded-xl bg-black px-4 py-2 text-sm font-medium text-white hover:bg-gray-800 disabled:opacity-60 dark:bg-white dark:text-black dark:hover:bg-gray-200">
                        {loading ? <RefreshCcw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />} {t.start}
                    </button>
                    <button onClick={cancel} disabled={!loading}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                        <Square className="h-4 w-4" /> {t.stop}
                    </button>
                </div>

                {/* 待办客户端调用（当进入 CLIENT_WAIT 时出现） */}
                {Array.isArray(pendingCalls) && pendingCalls.length > 0 && (
                    <section className="mt-6 rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
                        <div className="mb-2 flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
                            <ListChecks className="h-4 w-4" /> {t.pendingTitle}
                        </div>

                        <div className="space-y-4">
                            {pendingCalls.map((c, i) => {
                                const url = c?.args?.url;
                                return (
                                    <div key={c.callId} className="rounded-xl border p-3 dark:border-slate-700">
                                        <div className="flex items-center justify-between text-sm">
                                            <div className="font-medium">
                                                {c.name} <span className="opacity-60">#{c.callId}</span>
                                            </div>
                                            {c.hint && <div className="text-xs opacity-70">{c.hint}</div>}
                                        </div>

                                        {/* 查看原始 arguments */}
                                        <div className="mt-2 text-xs opacity-70">
                                            {t.argsSample}
                                            <pre className="mt-1 max-h-40 overflow-auto rounded bg-slate-50 p-2 dark:bg-slate-800/60">
                        {JSON.stringify(c.args ?? {}, null, 2)}
                      </pre>
                                        </div>

                                        {/* 编辑回传结果：status / reused / arguments / data */}
                                        <div className="mt-2 grid gap-2 md:grid-cols-5">
                                            {/* arguments */}
                                            <div className="md:col-span-2">
                                                <div className="mb-1 text-xs opacity-70">clientResults[{i}].arguments</div>
                                                <textarea
                                                    className="w-full rounded-xl border p-2 font-mono text-xs dark:border-slate-700 dark:bg-slate-800"
                                                    rows={5}
                                                    defaultValue={JSON.stringify(draftResults[i]?.arguments ?? {}, null, 2)}
                                                    onBlur={(e) => {
                                                        try {
                                                            const parsed = e.currentTarget.value.trim() ? JSON.parse(e.currentTarget.value) : {};
                                                            updateDraftResult(i, { arguments: parsed, error: undefined });
                                                        } catch (err: any) {
                                                            updateDraftResult(i, { error: err?.message || "Invalid JSON" });
                                                        }
                                                    }}
                                                />
                                            </div>

                                            {/* data */}
                                            <div className="md:col-span-2">
                                                <div className="mb-1 text-xs opacity-70">clientResults[{i}].data</div>
                                                <textarea
                                                    className="w-full rounded-xl border p-2 font-mono text-xs dark:border-slate-700 dark:bg-slate-800"
                                                    rows={5}
                                                    defaultValue={JSON.stringify(draftResults[i]?.data ?? { payload: { type: "text", value: "" } }, null, 2)}
                                                    onBlur={(e) => {
                                                        try {
                                                            const parsed = e.currentTarget.value.trim() ? JSON.parse(e.currentTarget.value) : {};
                                                            updateDraftResult(i, { data: parsed, error: undefined });
                                                        } catch (err: any) {
                                                            updateDraftResult(i, { error: err?.message || "Invalid JSON" });
                                                        }
                                                    }}
                                                />
                                            </div>

                                            {/* status / reused + 快捷填充 */}
                                            <div className="flex flex-col gap-2">
                                                <label className="text-xs">
                                                    <div className="mb-1 opacity-70">status</div>
                                                    <select
                                                        className="w-full rounded-xl border p-2 text-xs dark:border-slate-700 dark:bg-slate-800"
                                                        value={draftResults[i]?.status ?? "SUCCESS"}
                                                        onChange={(e) => updateDraftResult(i, { status: e.target.value as any })}
                                                    >
                                                        <option value="SUCCESS">SUCCESS</option>
                                                        <option value="ERROR">ERROR</option>
                                                    </select>
                                                </label>

                                                <label className="inline-flex items-center gap-2 text-xs">
                                                    <input
                                                        type="checkbox"
                                                        checked={!!draftResults[i]?.reused}
                                                        onChange={(e) => updateDraftResult(i, { reused: e.target.checked })}
                                                    />
                                                    reused
                                                </label>

                                                <button
                                                    onClick={() => {
                                                        const text = lang === "zh"
                                                            ? `已在新标签打开 ${url ?? ""}` : `Opened in a new tab ${url ?? ""}`;
                                                        updateDraftResult(i, { data: { payload: { type: "text", value: text } } });
                                                    }}
                                                    className="rounded-xl border px-2 py-1 text-xs hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800"
                                                    disabled={!url}
                                                    title="根据 URL 快速填充 data.payload"
                                                >
                                                    {t.fillFromArgs}
                                                </button>

                                                {draftResults[i]?.error && (
                                                    <div className="text-xs text-red-600 dark:text-red-300">{draftResults[i]?.error}</div>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>

                        <div className="mt-3">
                            <button
                                disabled={!canResume || loading}
                                onClick={() => continueWith(draftResults)}
                                className="inline-flex items-center gap-2 rounded-xl bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60 dark:bg-blue-500 dark:hover:bg-blue-400"
                            >
                                {loading ? <RefreshCcw className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />} {t.submitContinue}
                            </button>
                        </div>
                    </section>
                )}

                {/* 事件时间线 */}
                <section className="mt-6 rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
                    <div className="mb-2 flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
                        <MessagesSquare className="h-4 w-4" /> {t.eventsTitle} {aggEvents.length ? `(${aggEvents.length})` : ""}
                    </div>
                    {aggEvents.length === 0 ? (
                        <div className="px-2 py-6 text-sm opacity-70">{t.eventsEmpty}</div>
                    ) : (
                        <ul className="space-y-2">
                            {aggEvents.map((e, i) => (
                                <li key={i} className="rounded-xl border p-3 text-sm dark:border-slate-700">
                                    <div className="flex items-center justify-between">
                                        <div>
                                            <span className="font-medium">{e.type}</span>
                                            {e.ts ? <span className="ml-2 opacity-60">{String(e.ts)}</span> : null}
                                        </div>
                                        {e.type === "ERROR" && <span className="text-red-600 dark:text-red-300">{e.message ?? ""}</span>}
                                        {e.type === "FINISHED" && (
                                            <span className="flex items-center gap-1 text-emerald-600 dark:text-emerald-300">
                        <CheckCircle2 size={14} /> {t.finished}
                      </span>
                                        )}
                                    </div>

                                    {/* message */}
                                    {e.message && (
                                        <div className="mt-2 rounded-xl border bg-slate-50 p-2 dark:border-slate-700 dark:bg-slate-800">
                                            <div className="text-xs opacity-70 mb-1">message</div>
                                            <div className="whitespace-pre-wrap">{String(e.message)}</div>
                                        </div>
                                    )}

                                    {/* 详情：state 独占一行，其它占栅格 */}
                                    <div className="mt-2 grid gap-2 grid-cols-1 md:grid-cols-3">
                                        {e.state && (
                                            <div className="rounded-xl border bg-slate-50 p-2 text-xs dark:border-slate-700 dark:bg-slate-800 md:col-span-3">
                                                <div className="mb-1 font-medium opacity-70">state</div>
                                                <pre className="max-h-[60vh] overflow-auto">{JSON.stringify(e.state, null, 2)}</pre>
                                            </div>
                                        )}
                                        {e.transition && (
                                            <div className="rounded-xl border bg-slate-50 p-2 text-xs dark:border-slate-700 dark:bg-slate-800">
                                                <div className="mb-1 font-medium opacity-70">transition</div>
                                                <pre className="max-h-56 overflow-auto">{JSON.stringify(e.transition, null, 2)}</pre>
                                            </div>
                                        )}
                                        {e.pendingClientCalls && e.pendingClientCalls.length > 0 && (
                                            <div className="rounded-xl border bg-slate-50 p-2 text-xs dark:border-slate-700 dark:bg-slate-800">
                                                <div className="mb-1 font-medium opacity-70">pendingClientCalls</div>
                                                <pre className="max-h-56 overflow-auto">{JSON.stringify(e.pendingClientCalls, null, 2)}</pre>
                                            </div>
                                        )}
                                    </div>
                                </li>
                            ))}
                        </ul>
                    )}
                </section>
            </main>
        </div>
    );
}

const I18N = {
    zh: {
        title: "Step 流 & 客户端结果注入",
        subtitle: "NDJSON 流式编排；等待时可填写 clientResults（含 arguments/data/reused）",
        userId: "用户 ID",
        conversationId: "会话 ID",
        prompt: "用户输入",
        promptPh: "例如：帮我打开百度主页",
        start: "开始编排",
        stop: "停止当前流",
        running: "进行中…",
        pendingTitle: "待办的客户端调用",
        argsSample: "模型建议的参数（arguments）：",
        fillFromArgs: "用 URL 生成文本",
        submitContinue: "提交并继续",
        eventsTitle: "事件时间线（聚合）",
        eventsEmpty: "尚无事件，点击“开始编排”试试。",
        finished: "已结束",
        tools: {
            title: "客户端工具定义（可选）",
            add: "新增工具",
            quickOpenUrl: "示例：open_url",
            empty: "尚未添加任何客户端工具。",
            item: "工具",
            name: "函数名",
            desc: "描述",
            params: "参数 JSON（OpenAPI / JSON Schema）",
            paramsHint: "示例：{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"format\":\"uri\"}},\"required\":[\"url\"]}",
            remove: "删除",
        },
    },
    en: {
        title: "Step Flow & Client Results Injection",
        subtitle: "Stream NDJSON; when waiting, fill clientResults (with arguments/data/reused).",
        userId: "User ID",
        conversationId: "Conversation ID",
        prompt: "Prompt",
        promptPh: "e.g. Open Baidu homepage",
        start: "Start Orchestration",
        stop: "Stop Stream",
        running: "Running…",
        pendingTitle: "Pending Client Calls",
        argsSample: "Model-suggested arguments:",
        fillFromArgs: "From URL → text",
        submitContinue: "Submit & Continue",
        eventsTitle: "Event Timeline (Aggregated)",
        eventsEmpty: "No events yet. Click 'Start Orchestration' to run.",
        finished: "Finished",
        tools: {
            title: "Client Tools (optional)",
            add: "Add Tool",
            quickOpenUrl: "Example: open_url",
            empty: "No client tools yet.",
            item: "Tool",
            name: "Name",
            desc: "Description",
            params: "Parameters JSON (OpenAPI / JSON Schema)",
            paramsHint: "Example: {\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"format\":\"uri\"}},\"required\":[\"url\"]}",
            remove: "Remove",
        },
    },
} as const;

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
    return <div className={`flex items-center gap-2 rounded-xl border px-3 py-2 text-sm ${tone}`}>{icon} <span>{text}</span></div>;
}

function useNdjson(
    url: string,
    options?: { method?: "GET" | "POST"; body?: any; headers?: Record<string, string> }
) {
    const [events, setEvents] = React.useState<StepEvent[]>([]);
    const [loading, setLoading] = React.useState(false);
    const [error, setError] = React.useState<string | null>(null);
    const abortRef = React.useRef<AbortController | null>(null);

    React.useEffect(() => {
        if (!options) return;
        const ctrl = new AbortController();
        abortRef.current = ctrl;
        let cancelled = false;

        async function run() {
            setLoading(true); setError(null); setEvents([]);
            try {
                const resp = await fetch(url, {
                    method: options?.method ?? "POST",
                    headers: { Accept: "application/x-ndjson", "Content-Type": "application/json", ...(options?.headers ?? {}) },
                    body: options?.body ? JSON.stringify(options.body) : undefined,
                    signal: ctrl.signal,
                });
                if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`);
                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buf = "";
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buf += decoder.decode(value, { stream: true });
                    let idx: number;
                    while ((idx = buf.indexOf("\n")) >= 0) {
                        const line = buf.slice(0, idx).trim();
                        buf = buf.slice(idx + 1);
                        if (!line) continue;
                        try {
                            const evt = JSON.parse(line) as StepEvent;
                            if (!cancelled) setEvents((prev) => [...prev, evt]);
                        } catch {}
                    }
                }
                const tail = buf.trim();
                if (tail) {
                    try {
                        const evt = JSON.parse(tail) as StepEvent;
                        if (!cancelled) setEvents((prev) => [...prev, evt]);
                    } catch {}
                }
            } catch (e: any) {
                if (!cancelled) setError(String(e?.message ?? e));
            } finally {
                if (!cancelled) setLoading(false);
            }
        }
        run();
        return () => { cancelled = true; ctrl.abort(); };
    }, [url, JSON.stringify(options ?? {})]);

    const cancel = React.useCallback(() => abortRef.current?.abort(), []);
    return { events, loading, error, cancel };
}
