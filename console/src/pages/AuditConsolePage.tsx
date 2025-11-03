// src/pages/AuditConsolePage.tsx
import React, { useMemo, useState } from "react";
import {
    ShieldCheck, ShieldAlert, RefreshCcw, Languages, Link as LinkIcon,
    FileText, FileJson2, Download, Clipboard, ClipboardCheck, Table, Wand2
} from "lucide-react";

type Lang = "zh" | "en";

type BreakItem = {
    index: number;
    nodeId: string | null;
    createdAt: string | null;
    expectPrev: string | null;
    actualPrev: string | null;
    expectHash: string | null;
    actualHash: string | null;
    prevMatch: boolean;
    hashMatch: boolean;
};

type Report = {
    userId: string;
    conversationId: string;
    totalNodes: number;
    ok: boolean;
    firstBadIndex: number | null;
    breaks: BreakItem[];
    tailHash: string | null;
};

const I18N = {
    zh: {
        title: "Javelin 审计控制台",
        subtitle: "校验对话哈希链 · 导出可复验数据",
        fields: { userId: "用户 ID", convId: "会话 ID" },
        actions: {
            verify: "校验链条",
            tail: "查看尾哈希",
            export: "导出",
            csv: "CSV",
            json: "JSON",
            ndjson: "NDJSON",
            copy: "复制",
            copied: "已复制",
        },
        banners: {
            running: "正在执行…",
            ok: "校验通过",
            bad: "校验失败",
        },
        summary: {
            total: "总节点",
            result: "结果",
            firstBad: "第一个坏点索引",
            tail: "尾哈希",
        },
        breaks: {
            title: "坏点列表",
            index: "索引",
            nodeId: "节点ID",
            createdAt: "创建时间",
            prevMatch: "前哈希匹配",
            hashMatch: "本哈希匹配",
            expectPrev: "期望 prev",
            actualPrev: "实际 prev",
            expectHash: "期望 hash",
            actualHash: "实际 hash",
            none: "没有发现断点 🎉",
        },
        tips: "提示：校验通过后可记录 tailHash，后续可做快速对账。",
    },
    en: {
        title: "Javelin Audit Console",
        subtitle: "Verify conversation hash chain · Export reproducible data",
        fields: { userId: "User ID", convId: "Conversation ID" },
        actions: {
            verify: "Verify Chain",
            tail: "Get Tail Hash",
            export: "Export",
            csv: "CSV",
            json: "JSON",
            ndjson: "NDJSON",
            copy: "Copy",
            copied: "Copied",
        },
        banners: {
            running: "Running…",
            ok: "Verification passed",
            bad: "Verification failed",
        },
        summary: {
            total: "Total Nodes",
            result: "Result",
            firstBad: "First Bad Index",
            tail: "Tail Hash",
        },
        breaks: {
            title: "Breakpoints",
            index: "#",
            nodeId: "Node ID",
            createdAt: "Created At",
            prevMatch: "Prev Match",
            hashMatch: "Hash Match",
            expectPrev: "Expected prev",
            actualPrev: "Actual prev",
            expectHash: "Expected hash",
            actualHash: "Actual hash",
            none: "No breaks found 🎉",
        },
        tips: "Tip: After a pass, record tailHash for quick reconciliation later.",
    },
} as const;

export default function AuditConsolePage() {
    const [lang, setLang] = useState<Lang>(() =>
        typeof navigator !== "undefined" && navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en"
    );
    const t = I18N[lang];

    const [userId, setUserId] = useState<string>("");
    const [conversationId, setConversationId] = useState<string>("");

    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [report, setReport] = useState<Report | null>(null);
    const [tail, setTail] = useState<string | null>(null);
    const [copied, setCopied] = useState(false);

    const canRun = userId.trim().length > 0 && conversationId.trim().length > 0;

    const qs = useMemo(
        () => new URLSearchParams({ userId, conversationId }).toString(),
        [userId, conversationId]
    );

    async function doVerify() {
        if (!canRun) return;
        setLoading(true); setError(null); setReport(null);
        try {
            const res = await fetch(`/audit/verify?${qs}`, { headers: { Accept: "application/json" } });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const json = (await res.json()) as Report;
            setReport(json);
            setTail(json.tailHash ?? null);
        } catch (e: any) {
            setError(e?.message ?? String(e));
        } finally {
            setLoading(false);
        }
    }

    async function getTail() {
        if (!canRun) return;
        setLoading(true); setError(null); setTail(null);
        try {
            const res = await fetch(`/audit/tail?${qs}`, { headers: { Accept: "application/json" } });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const json = await res.json();
            setTail(json.tailHash ?? null);
        } catch (e: any) {
            setError(e?.message ?? String(e));
        } finally {
            setLoading(false);
        }
    }

    function openExport(kind: "csv" | "json" | "ndjson") {
        if (!canRun) return;
        const map: Record<string, string> = {
            csv: `/audit/export/csv?${qs}&format=csv`,
            json: `/audit/export/json?${qs}&format=json`,
            ndjson: `/audit/export/ndjson?${qs}&format=ndjson`,
        };
        window.open(map[kind], "_blank");
    }
    async function copyTail() {
        if (!tail) return;
        try {
            await navigator.clipboard.writeText(tail);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        } catch { /* ignore */ }
    }

    const ok = !!report && report.ok === true;

    return (
        <div className="min-h-screen w-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
            {/* Header */}
            <header className="sticky top-0 z-10 border-b bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:border-slate-800 dark:bg-slate-900/80 dark:supports-[backdrop-filter]:bg-slate-900/60">
                <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
                    <div className="flex items-center gap-3">
                        <div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-tr from-emerald-500 to-indigo-500 text-white shadow-sm">
                            <ShieldCheck size={18} />
                        </div>
                        <div>
                            <h1 className="text-lg font-semibold leading-tight">{t.title}</h1>
                            <p className="text-xs text-slate-500 dark:text-slate-400">{t.subtitle}</p>
                        </div>
                    </div>

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
            </header>

            {/* Body */}
            <main className="mx-auto max-w-6xl px-4 py-6">
                {/* banners */}
                <div className="mb-4 space-y-2">
                    {loading && <Banner icon={<RefreshCcw className="animate-spin" size={16} />} text={t.banners.running} color="slate" />}
                    {error && <Banner icon={<ShieldAlert size={16} />} text={error} color="red" />}
                    {report && (
                        <Banner
                            icon={ok ? <ShieldCheck size={16} /> : <ShieldAlert size={16} />}
                            text={ok ? t.banners.ok : t.banners.bad}
                            color={ok ? "green" : "red"}
                        />
                    )}
                </div>

                {/* Form */}
                <Section title="">
                    <div className="grid gap-4 md:grid-cols-3">
                        <Field label={t.fields.userId}>
                            <div className="relative">
                                <input
                                    value={userId}
                                    onChange={(e) => setUserId(e.target.value)}
                                    placeholder="u1"
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                                <LinkIcon className="absolute right-2 top-2.5 text-slate-400" size={18} />
                            </div>
                        </Field>
                        <Field label={t.fields.convId}>
                            <div className="relative">
                                <input
                                    value={conversationId}
                                    onChange={(e) => setConversationId(e.target.value)}
                                    placeholder="c1"
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                                <LinkIcon className="absolute right-2 top-2.5 text-slate-400" size={18} />
                            </div>
                        </Field>

                        <div className="flex items-end gap-2">
                            <button
                                onClick={doVerify}
                                disabled={!canRun || loading}
                                className="inline-flex items-center gap-2 rounded-xl bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60 dark:bg-blue-500 dark:hover:bg-blue-400"
                            >
                                {loading ? <RefreshCcw className="h-4 w-4 animate-spin" /> : <ShieldAlert className="h-4 w-4" />}
                                {t.actions.verify}
                            </button>
                            <button
                                onClick={getTail}
                                disabled={!canRun || loading}
                                className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                            >
                                <LinkIcon className="h-4 w-4" />
                                {t.actions.tail}
                            </button>
                        </div>
                    </div>
                </Section>

                {/* Tail */}
                {tail && (
                    <div className="mb-6 flex items-center justify-between rounded-xl border border-slate-200 bg-white p-3 text-sm dark:border-slate-800 dark:bg-slate-900">
                        <div className="truncate">
                            <span className="mr-2 font-medium">{t.summary.tail}:</span>
                            <code className="break-all">{tail}</code>
                        </div>
                        <button
                            onClick={async () => {
                                await copyTail();
                            }}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                            title={t.actions.copy}
                        >
                            {copied ? <ClipboardCheck className="h-4 w-4" /> : <Clipboard className="h-4 w-4" />}
                            {copied ? t.actions.copied : t.actions.copy}
                        </button>
                    </div>
                )}

                {/* Export */}
                <div className="mb-6 inline-flex items-center gap-2">
                    <span className="mr-2 text-sm text-slate-500 dark:text-slate-400">{t.actions.export}:</span>
                    <button
                        onClick={() => openExport("csv")}
                        disabled={!canRun}
                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                    >
                        <FileText className="h-4 w-4" />
                        {t.actions.csv}
                    </button>
                    <button
                        onClick={() => openExport("json")}
                        disabled={!canRun}
                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                    >
                        <FileJson2 className="h-4 w-4" />
                        {t.actions.json}
                    </button>
                    <button
                        onClick={() => openExport("ndjson")}
                        disabled={!canRun}
                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                    >
                        <Download className="h-4 w-4" />
                        {t.actions.ndjson}
                    </button>
                </div>

                {/* Summary */}
                {report && (
                    <div className="mb-6 grid gap-4 md:grid-cols-2">
                        <Card title="Summary">
                            <Snap k="userId" v={report.userId} />
                            <Snap k="conversationId" v={report.conversationId} />
                            <Snap k={t.summary.total} v={String(report.totalNodes)} />
                            <Snap k={t.summary.result} v={ok ? (t.banners.ok) : (t.banners.bad)} />
                            <Snap k={t.summary.firstBad} v={report.firstBadIndex ?? "-"} />
                            <Snap k={t.summary.tail} v={report.tailHash ?? "-"} />
                        </Card>
                    </div>
                )}

                {/* Breaks */}
                {report && (
                    <div className="rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
                        <div className="mb-3 flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
                            <Table className="h-4 w-4" />
                            {t.breaks.title} {report.breaks?.length ? `(${report.breaks.length})` : ""}
                        </div>

                        {Array.isArray(report.breaks) && report.breaks.length > 0 ? (
                            <div className="overflow-auto">
                                <table className="min-w-full text-sm">
                                    <thead className="bg-slate-50 dark:bg-slate-800/50">
                                    <tr>
                                        <Th>{t.breaks.index}</Th>
                                        <Th>{t.breaks.nodeId}</Th>
                                        <Th>{t.breaks.createdAt}</Th>
                                        <Th>{t.breaks.prevMatch}</Th>
                                        <Th>{t.breaks.hashMatch}</Th>
                                        <Th>{t.breaks.expectPrev}</Th>
                                        <Th>{t.breaks.actualPrev}</Th>
                                        <Th>{t.breaks.expectHash}</Th>
                                        <Th>{t.breaks.actualHash}</Th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    {report.breaks.map((b) => (
                                        <tr key={b.index} className="border-t border-slate-200 dark:border-slate-800">
                                            <Td>{b.index}</Td>
                                            <Td><code>{b.nodeId ?? "-"}</code></Td>
                                            <Td><code>{b.createdAt ?? "-"}</code></Td>
                                            <Td>
                          <span className={b.prevMatch ? "text-emerald-600 dark:text-emerald-300" : "text-red-600 dark:text-red-300"}>
                            {String(b.prevMatch)}
                          </span>
                                            </Td>
                                            <Td>
                          <span className={b.hashMatch ? "text-emerald-600 dark:text-emerald-300" : "text-red-600 dark:text-red-300"}>
                            {String(b.hashMatch)}
                          </span>
                                            </Td>
                                            <Td><code className="break-all text-xs">{b.expectPrev ?? "-"}</code></Td>
                                            <Td><code className="break-all text-xs">{b.actualPrev ?? "-"}</code></Td>
                                            <Td><code className="break-all text-xs">{b.expectHash ?? "-"}</code></Td>
                                            <Td><code className="break-all text-xs">{b.actualHash ?? "-"}</code></Td>
                                        </tr>
                                    ))}
                                    </tbody>
                                </table>
                            </div>
                        ) : (
                            <div className="px-4 py-6 text-sm opacity-75">{t.breaks.none}</div>
                        )}
                    </div>
                )}

                {/* Tips */}
                <div className="mt-8 text-xs text-slate-500 dark:text-slate-400 flex items-center gap-3">
                    <Wand2 className="h-4 w-4" />
                    <span>{t.tips}</span>
                </div>
            </main>
        </div>
    );
}

/* ---------- UI atoms (复用 AdminConfigConsole 的风格) ---------- */
function Section({ title, children }: { title?: string; children: React.ReactNode }) {
    return (
        <section className="py-4">
            {title ? <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">{title}</div> : null}
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

function Card({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div className="rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">{title}</div>
            <div className="grid grid-cols-2 gap-3 text-sm md:grid-cols-3">{children}</div>
        </div>
    );
}

function Snap({ k, v }: { k: string; v: any }) {
    return (
        <div className="rounded-xl border bg-slate-50 p-2 dark:border-slate-700 dark:bg-slate-800">
            <div className="text-[11px] uppercase tracking-wide text-slate-400 dark:text-slate-400">{k}</div>
            <div className="break-words font-medium">{String(v ?? "-")}</div>
        </div>
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
            {icon} <span>{text}</span>
        </div>
    );
}

function Th({ children }: { children: React.ReactNode }) {
    return <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">{children}</th>;
}
function Td({ children }: { children: React.ReactNode }) {
    return <td className="px-3 py-2 align-top">{children}</td>;
}
