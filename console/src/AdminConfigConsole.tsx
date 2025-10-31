import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
    Save, RefreshCw, Wand2, Rocket, ShieldCheck,
    KeyRound, Link, Timer, Wrench, Eye, EyeOff, Plus, Trash2
} from "lucide-react";

/**
 * AdminConfigConsole — Clean & Minimal
 * Tailwind-first，无外部 UI 套件；细腻动效；响应式。
 * 需要的后端接口：
 *  GET  /admin/config         -> { runtime:{...}, effective:{...} }
 *  PUT  /admin/config         -> 合并语义（仅更新传入字段）
 *  POST /admin/reload         -> 触发一次“按当前值”广播
 */
export default function AdminConfigConsole() {
    // 状态
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [reloading, setReloading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [okMsg, setOkMsg] = useState<string | null>(null);

    // 后端返回
    const [runtime, setRuntime] = useState<any | null>(null);
    const [effective, setEffective] = useState<any | null>(null);

    // 表单
    const [compatibility, setCompatibility] = useState<string>("OPENAI");
    const [model, setModel] = useState<string>("");
    const [toolsMaxLoops, setToolsMaxLoops] = useState<number>(2);
    const [baseUrl, setBaseUrl] = useState<string>("");
    const [newApiKey, setNewApiKey] = useState<string>("");
    const [apiKeyMasked, setApiKeyMasked] = useState<string | null>(null);
    const [clientTimeoutMs, setClientTimeoutMs] = useState<number | "">("");
    const [streamTimeoutMs, setStreamTimeoutMs] = useState<number | "">("");
    const [toolToggles, setToolToggles] = useState<Record<string, boolean>>({});
    const [showDiff, setShowDiff] = useState<boolean>(false);

    // 加载
    const load = async () => {
        setLoading(true);
        setError(null);
        setOkMsg(null);
        try {
            const res = await fetch("/admin/config", { headers: { Accept: "application/json" } });
            if (!res.ok) throw new Error(await res.text());
            const data = await res.json();
            const r = data.runtime ?? {};
            const e = data.effective ?? {};
            setRuntime(r);
            setEffective(e);
            setCompatibility(r.compatibility ?? e.compatibility ?? "OPENAI");
            setModel(r.model ?? e.model ?? "");
            setToolsMaxLoops(Number(r.toolsMaxLoops ?? e.toolsMaxLoops ?? 2));
            setBaseUrl(r.baseUrl ?? "");
            setApiKeyMasked(r.apiKeyMasked ?? null);
            setNewApiKey("");
            setClientTimeoutMs(r.clientTimeoutMs ?? e.clientTimeoutMs ?? "");
            setStreamTimeoutMs(r.streamTimeoutMs ?? e.streamTimeoutMs ?? "");
            setToolToggles(r.toolToggles ?? {});
        } catch (e: any) {
            setError(e.message || String(e));
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { load(); }, []);

    // 计算将要提交的差异
    const diffPayload = useMemo(() => {
        if (!runtime) return null;
        const payload: any = {};
        const push = (k: string, v: any, cur: any) => {
            if (v === "") return; // 空串：表示“不覆盖”
            if (JSON.stringify(v) !== JSON.stringify(cur)) payload[k] = v;
        };
        push("compatibility", compatibility || undefined, runtime.compatibility ?? undefined);
        push("model", model || undefined, runtime.model ?? undefined);
        push("toolsMaxLoops", Number.isFinite(Number(toolsMaxLoops)) ? Number(toolsMaxLoops) : undefined, runtime.toolsMaxLoops ?? undefined);
        push("baseUrl", baseUrl || undefined, runtime.baseUrl ?? undefined);
        push("clientTimeoutMs", clientTimeoutMs === "" ? undefined : Number(clientTimeoutMs), runtime.clientTimeoutMs ?? undefined);
        push("streamTimeoutMs", streamTimeoutMs === "" ? undefined : Number(streamTimeoutMs), runtime.streamTimeoutMs ?? undefined);
        if (newApiKey && newApiKey.trim().length > 0) payload.apiKey = newApiKey.trim();
        if (JSON.stringify(toolToggles || {}) !== JSON.stringify(runtime.toolToggles || {})) payload.toolToggles = toolToggles;
        return payload;
    }, [runtime, compatibility, model, toolsMaxLoops, baseUrl, clientTimeoutMs, streamTimeoutMs, newApiKey, toolToggles]);

    // 操作
    const save = async () => {
        if (!diffPayload) return;
        setSaving(true);
        setError(null);
        setOkMsg(null);
        try {
            const res = await fetch("/admin/config", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(diffPayload),
            });
            if (!res.ok) throw new Error(await res.text());
            setOkMsg("已保存并应用");
            await load();
        } catch (e: any) {
            setError(e.message || String(e));
        } finally {
            setSaving(false);
        }
    };

    const reload = async () => {
        setReloading(true);
        setError(null);
        setOkMsg(null);
        try {
            const res = await fetch("/admin/reload", { method: "POST" });
            if (!res.ok) throw new Error(await res.text());
            setOkMsg("已触发重载");
        } catch (e: any) {
            setError(e.message || String(e));
        } finally {
            setReloading(false);
        }
    };

    const resetForm = () => {
        if (!runtime || !effective) return;
        setCompatibility(runtime.compatibility ?? effective.compatibility ?? "OPENAI");
        setModel(runtime.model ?? effective.model ?? "");
        setToolsMaxLoops(Number(runtime.toolsMaxLoops ?? effective.toolsMaxLoops ?? 2));
        setBaseUrl(runtime.baseUrl ?? "");
        setNewApiKey("");
        setApiKeyMasked(runtime.apiKeyMasked ?? null);
        setClientTimeoutMs(runtime.clientTimeoutMs ?? effective.clientTimeoutMs ?? "");
        setStreamTimeoutMs(runtime.streamTimeoutMs ?? effective.streamTimeoutMs ?? "");
        setToolToggles(runtime.toolToggles ?? {});
    };

    // toggles 辅助
    const addToggle = () => {
        const name = prompt("输入工具名（function name）:");
        if (!name) return;
        setToolToggles(prev => ({ ...prev, [name]: true }));
    };
    const removeToggle = (k: string) => {
        const next = { ...(toolToggles || {}) };
        delete next[k];
        setToolToggles(next);
    };

    return (
        <div className="min-h-[80vh] bg-gradient-to-b from-white to-slate-50">
            {/* Header */}
            <header className="sticky top-0 z-10 backdrop-blur supports-[backdrop-filter]:bg-white/60 bg-white/80 border-b">
                <div className="mx-auto max-w-6xl px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="h-9 w-9 rounded-2xl bg-gradient-to-tr from-blue-500 to-indigo-500 text-white grid place-items-center shadow-sm">
                            <Wrench size={18} />
                        </div>
                        <div>
                            <h1 className="text-lg font-semibold leading-tight">Javelin 管理控制台</h1>
                            <p className="text-xs text-slate-500">运行时配置 · 热更新 · 安全打码</p>
                        </div>
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={reload}
                            disabled={reloading}
                            className={`inline-flex items-center gap-2 rounded-xl border px-3 py-1.5 text-sm ${reloading ? "opacity-60" : "hover:bg-slate-50"}`}
                            title="触发一次 Reload 广播"
                        >
                            <RefreshCw size={16} /> {reloading ? "重载中…" : "重载"}
                        </button>
                        <button
                            onClick={load}
                            className="inline-flex items-center gap-2 rounded-xl border px-3 py-1.5 text-sm hover:bg-slate-50"
                            title="刷新配置"
                        >
                            <Rocket size={16} /> 刷新
                        </button>
                    </div>
                </div>
            </header>

            {/* Body */}
            <main className="mx-auto max-w-6xl px-4 py-6">
                {/* 状态条 */}
                <div className="space-y-2 mb-4">
                    {loading && (<Banner icon={<RefreshCw className="animate-spin" size={16} />} text="正在加载配置…" color="slate" />)}
                    {error && <Banner icon={<ShieldCheck size={16} />} text={error} color="red" />}
                    {okMsg && <Banner icon={<Wand2 size={16} />} text={okMsg} color="green" />}
                </div>

                {/* 快照 */}
                {effective && (
                    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="mb-6 grid gap-4 md:grid-cols-2">
                        <Card title="实际生效 (effective)">
                            <Snap k="compatibility" v={effective.compatibility} />
                            <Snap k="model" v={effective.model} />
                            <Snap k="toolsMaxLoops" v={String(effective.toolsMaxLoops)} />
                            <Snap k="clientTimeoutMs" v={String(effective.clientTimeoutMs ?? "-")} />
                            <Snap k="streamTimeoutMs" v={String(effective.streamTimeoutMs ?? "-")} />
                        </Card>
                        <Card title="覆盖层 (runtime)">
                            <Snap k="compatibility" v={runtime?.compatibility ?? "-"} />
                            <Snap k="model" v={runtime?.model ?? "-"} />
                            <Snap k="toolsMaxLoops" v={String(runtime?.toolsMaxLoops ?? "-")} />
                            <Snap k="baseUrl" v={runtime?.baseUrl ?? "-"} />
                            <Snap k="apiKeyMasked" v={runtime?.apiKeyMasked ?? "-"} />
                            <Snap k="clientTimeoutMs" v={String(runtime?.clientTimeoutMs ?? "-")} />
                            <Snap k="streamTimeoutMs" v={String(runtime?.streamTimeoutMs ?? "-")} />
                        </Card>
                    </motion.div>
                )}

                {/* 表单 */}
                <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="rounded-2xl border bg-white p-5 shadow-sm">
                    <Section title="基础设置">
                        <div className="grid gap-4 md:grid-cols-3">
                            <Field label="兼容模式 (compatibility)">
                                <select value={compatibility} onChange={(e) => setCompatibility(e.target.value)} className="w-full rounded-xl border p-2">
                                    <option value="OPENAI">OPENAI</option>
                                    <option value="OLLAMA">OLLAMA</option>
                                </select>
                            </Field>
                            <Field label="模型名 (model)">
                                <input value={model} onChange={(e) => setModel(e.target.value)} placeholder="qwen3:8b / gpt-4o-mini / ..." className="w-full rounded-xl border p-2" />
                            </Field>
                            <Field label="工具循环上限 (toolsMaxLoops)">
                                <input type="number" value={toolsMaxLoops} onChange={(e) => setToolsMaxLoops(Number(e.target.value))} className="w-full rounded-xl border p-2" />
                            </Field>
                        </div>
                    </Section>

                    <Section title="网络与超时（可选覆盖）">
                        <div className="grid gap-4 md:grid-cols-3">
                            <Field label="Base URL (覆盖)">
                                <div className="relative">
                                    <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} className="w-full rounded-xl border p-2 pr-9" />
                                    <Link className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                </div>
                            </Field>
                            <Field label="新 API Key (不会显示旧值)">
                                <div className="relative">
                                    <input value={newApiKey} onChange={(e) => setNewApiKey(e.target.value)} placeholder={apiKeyMasked ? `当前：${apiKeyMasked}` : "未设置"} className="w-full rounded-xl border p-2 pr-9" />
                                    <KeyRound className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                </div>
                            </Field>
                            <div className="grid grid-cols-2 gap-4">
                                <Field label="clientTimeoutMs">
                                    <div className="relative">
                                        <input type="number" value={clientTimeoutMs as any} onChange={(e) => setClientTimeoutMs(e.target.value === "" ? "" : Number(e.target.value))} className="w-full rounded-xl border p-2 pr-9" />
                                        <Timer className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                    </div>
                                </Field>
                                <Field label="streamTimeoutMs">
                                    <div className="relative">
                                        <input type="number" value={streamTimeoutMs as any} onChange={(e) => setStreamTimeoutMs(e.target.value === "" ? "" : Number(e.target.value))} className="w-full rounded-xl border p-2 pr-9" />
                                        <Timer className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                    </div>
                                </Field>
                            </div>
                        </div>
                    </Section>

                    <Section title="工具开关 (toolToggles)">
                        <div className="space-y-3">
                            <div className="flex items-center gap-3">
                                <button onClick={addToggle} className="inline-flex items-center gap-2 rounded-xl border px-3 py-1.5 text-sm hover:bg-slate-50">
                                    <Plus size={16} /> 新增
                                </button>
                                <span className="text-xs text-slate-500">未配置的默认启用(true)。</span>
                            </div>
                            <div className="grid gap-2 md:grid-cols-2">
                                {Object.keys(toolToggles).length === 0 && (
                                    <div className="text-sm text-slate-500">（暂无显式开关）</div>
                                )}
                                {Object.entries(toolToggles).map(([k, v]) => (
                                    <div key={k} className="flex items-center justify-between rounded-xl border p-3">
                                        <div className="font-mono text-sm break-words pr-4">{k}</div>
                                        <div className="flex items-center gap-3">
                                            <label className="text-sm flex items-center gap-1">
                                                <input type="checkbox" checked={!!v} onChange={(e) => setToolToggles({ ...toolToggles, [k]: e.target.checked })} />
                                                启用
                                            </label>
                                            <button onClick={() => removeToggle(k)} className="text-xs text-red-600 hover:underline inline-flex items-center gap-1">
                                                <Trash2 size={14} /> 删除
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </Section>

                    {/* 操作区 */}
                    <div className="mt-5 flex flex-wrap items-center gap-3">
                        <button
                            onClick={save}
                            disabled={saving || !diffPayload}
                            className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-white ${saving || !diffPayload ? "bg-slate-400" : "bg-blue-600 hover:bg-blue-700"}`}
                        >
                            <Save size={16} /> {saving ? "保存中…" : "保存配置"}
                        </button>
                        <button onClick={resetForm} className="inline-flex items-center gap-2 rounded-xl border px-4 py-2 hover:bg-slate-50">
                            <RefreshCw size={16} /> 撤销未保存更改
                        </button>
                        <button onClick={() => setShowDiff(!showDiff)} className="inline-flex items-center gap-2 rounded-xl border px-4 py-2 hover:bg-slate-50">
                            {showDiff ? <EyeOff size={16} /> : <Eye size={16} />} {showDiff ? "隐藏变更" : "查看将提交的变更"}
                        </button>
                        {diffPayload && (
                            <span className="text-xs text-slate-500 self-center">将提交: {Object.keys(diffPayload).join(", ") || "<无>"}</span>
                        )}
                    </div>

                    {showDiff && (
                        <motion.pre initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="mt-4 rounded-xl bg-slate-900 text-slate-100 p-4 overflow-auto text-xs">
                            {JSON.stringify(diffPayload, null, 2)}
                        </motion.pre>
                    )}
                </motion.div>
            </main>
        </div>
    );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div className="py-4">
            <div className="mb-3 text-sm font-medium text-slate-700">{title}</div>
            {children}
        </div>
    );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <label className="block text-sm">
            <div className="mb-1 text-slate-500">{label}</div>
            {children}
        </label>
    );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div className="rounded-2xl border bg-white p-4 shadow-sm">
            <div className="mb-3 text-sm font-medium text-slate-700">{title}</div>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3 text-sm">{children}</div>
        </div>
    );
}

function Snap({ k, v }: { k: string; v: any }) {
    return (
        <div className="rounded-xl border bg-slate-50 p-2">
            <div className="text-[11px] uppercase tracking-wide text-slate-400">{k}</div>
            <div className="font-medium break-words">{String(v ?? "-")}</div>
        </div>
    );
}

function Banner({ icon, text, color }: { icon: React.ReactNode; text: string; color: "slate" | "green" | "red" }) {
    const tone = color === "green" ? "bg-emerald-50 text-emerald-700 border-emerald-200"
        : color === "red" ? "bg-red-50 text-red-700 border-red-200"
            : "bg-slate-50 text-slate-700 border-slate-200";
    return (
        <div className={`flex items-center gap-2 rounded-xl border px-3 py-2 text-sm ${tone}`}>
            {icon}
            <span>{text}</span>
        </div>
    );
}
