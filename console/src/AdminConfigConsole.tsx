import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
    Save,
    RefreshCw,
    Wand2,
    Rocket,
    ShieldCheck,
    KeyRound,
    Link,
    Timer,
    Wrench,
    Eye,
    EyeOff,
    Plus,
    Trash2,
    Languages
} from "lucide-react";

/**
 * AdminConfigConsole — bilingual (ZH / EN), improved layout
 * Tailwind: darkMode:'media'
 * Endpoints:
 *  GET  /admin/config         -> { runtime:{...}, effective:{...} }
 *  PUT  /admin/config         -> merge semantics
 *  POST /admin/reload
 */
export default function AdminConfigConsole() {
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

    const locales = {
        zh: {
            title: "Javelin 配置控制台",
            subtitle: "运行时覆盖 · 生效配置 · 安全管理",
            actions: {
                reload: "重载",
                reloading: "重载中...",
                refresh: "刷新",
                save: "保存配置",
                saving: "保存中...",
                revert: "撤销未保存更改",
                restore: "恢复默认",
                restoring: "恢复中...",
                diffOpen: "查看将要提交的 Diff",
                diffClose: "关闭 Diff",
                willSubmit: "将提交",
            },
            banners: {
                loading: "正在加载配置",
                saved: "已保存配置",
                reloaded: "已触发 Reload",
                restored: "已恢复为默认配置",
            },
            sections: {
                snapshots: {
                    effective: "实际生效 (effective)",
                    runtime: "覆盖（runtime）",
                },
                basics: "基础设置",
                network: "网络与超时（可选覆盖）",
                toggles: "工具开关 (toolToggles)",
                none: "（暂无显式开关）",
                defaultOn: "未配置的默认启用 (true)",
                enable: "启用",
            },
            fields: {
                compatibility: "兼容模式 (compatibility)",
                model: "模型（model）",
                loops: "工具循环上限 (toolsMaxLoops)",
                baseUrl: "Base URL（覆盖）",
                newKey: "新 API Key（不会显示旧值）",
                clientTimeout: "clientTimeoutMs",
                streamTimeout: "streamTimeoutMs",
            },
            tooltips: {
                reload: "触发一次 Reload 广播",
                refresh: "刷新配置",
            },
            placeholders: {
                model: "qwen2:7b / gpt-4o-mini / ...",
                keyUnset: "未设置",
                keyMask: (m: string) => `当前：${m}`,
            },
            confirm: {
                restore: "恢复默认？这将清空所有运行时覆盖。",
            },
        },
        en: {
            title: "Javelin Config Console",
            subtitle: "Runtime Overrides · Effective Config · Security",
            actions: {
                reload: "Reload",
                reloading: "Reloading...",
                refresh: "Refresh",
                save: "Save",
                saving: "Saving...",
                revert: "Revert Unsaved",
                restore: "Restore Defaults",
                restoring: "Restoring...",
                diffOpen: "Show Pending Diff",
                diffClose: "Hide Diff",
                willSubmit: "Will submit",
            },
            banners: {
                loading: "Loading configuration",
                saved: "Configuration saved",
                reloaded: "Reload triggered",
                restored: "Restored to defaults",
            },
            sections: {
                snapshots: {
                    effective: "Effective",
                    runtime: "Runtime Overrides",
                },
                basics: "Basics",
                network: "Network & Timeouts (optional overrides)",
                toggles: "Tool Toggles (toolToggles)",
                none: "(No explicit toggles yet)",
                defaultOn: "Unspecified tools default to enabled (true)",
                enable: "Enable",
            },
            fields: {
                compatibility: "Compatibility",
                model: "Model",
                loops: "Max Tool Loops",
                baseUrl: "Base URL (override)",
                newKey: "New API Key (old value hidden)",
                clientTimeout: "clientTimeoutMs",
                streamTimeout: "streamTimeoutMs",
            },
            tooltips: {
                reload: "Broadcast a reload",
                refresh: "Refresh config",
            },
            placeholders: {
                model: "qwen2:7b / gpt-4o-mini / ...",
                keyUnset: "Not set",
                keyMask: (m: string) => `Current: ${m}`,
            },
            confirm: {
                restore: "Restore defaults? This will clear all runtime overrides.",
            },
        },
    } as const;

    const t = locales[lang];

    // ===== state =====
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [reloading, setReloading] = useState(false);
    const [resetting, setResetting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [okMsg, setOkMsg] = useState<string | null>(null);

    // backend
    const [runtime, setRuntime] = useState<any | null>(null);
    const [effective, setEffective] = useState<any | null>(null);

    // form
    const [compatibility, setCompatibility] = useState<string>("OPENAI");
    const [model, setModel] = useState<string>("");
    const [toolsMaxLoops, setToolsMaxLoops] = useState<number>(2);
    const [baseUrl, setBaseUrl] = useState<string>("");
    const [newApiKey, setNewApiKey] = useState<string>("");
    const [apiKeyMasked, setApiKeyMasked] = useState<string | null>(null);
    const [clientTimeoutMs, setClientTimeoutMs] = useState<number | "">("");
    const [streamTimeoutMs, setStreamTimeoutMs] = useState<number | "">("");
    const [toolToggles, setToolToggles] = useState<Record<string, boolean>>({});
    const [availableTools, setAvailableTools] = useState<string[]>([]);
    const [showDiff, setShowDiff] = useState<boolean>(false);

    // ===== load =====
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
            const tools: string[] = Array.isArray(data.availableTools) ? data.availableTools : [];
            setRuntime(r);
            setEffective(e);
            setAvailableTools(tools);
            setCompatibility(r.compatibility ?? e.compatibility ?? "OPENAI");
            setModel(r.model ?? e.model ?? "");
            setToolsMaxLoops(Number(r.toolsMaxLoops ?? e.toolsMaxLoops ?? 2));
            setBaseUrl(r.baseUrl ?? e.baseUrl ?? "");
            setApiKeyMasked(r.apiKeyMasked ?? e.apiKeyMasked ?? null);
            setNewApiKey("");
            setClientTimeoutMs(r.clientTimeoutMs ?? e.clientTimeoutMs ?? "");
            setStreamTimeoutMs(r.streamTimeoutMs ?? e.streamTimeoutMs ?? "");
            setToolToggles(r.toolToggles ?? {});
        } catch (e: any) {
            setError(e?.message || String(e));
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { load(); }, []);

    // ===== diff =====
    const diffPayload = useMemo(() => {
        if (!runtime) return null;
        const payload: any = {};
        const push = (k: string, v: any, cur: any) => {
            if (v === "") return; // empty string means "do not override"
            if (JSON.stringify(v) !== JSON.stringify(cur)) payload[k] = v;
        };
        push("compatibility", compatibility || undefined, runtime.compatibility ?? undefined);
        push("model", model || undefined, runtime.model ?? undefined);
        push(
            "toolsMaxLoops",
            Number.isFinite(Number(toolsMaxLoops)) ? Number(toolsMaxLoops) : undefined,
            runtime.toolsMaxLoops ?? undefined
        );
        push("baseUrl", baseUrl || undefined, runtime.baseUrl ?? undefined);
        push(
            "clientTimeoutMs",
            clientTimeoutMs === "" ? undefined : Number(clientTimeoutMs),
            runtime.clientTimeoutMs ?? undefined
        );
        push(
            "streamTimeoutMs",
            streamTimeoutMs === "" ? undefined : Number(streamTimeoutMs),
            runtime.streamTimeoutMs ?? undefined
        );
        if (newApiKey && newApiKey.trim().length > 0) payload.apiKey = newApiKey.trim();
        if (JSON.stringify(toolToggles || {}) !== JSON.stringify(runtime.toolToggles || {})) payload.toolToggles = toolToggles;
        return payload;
    }, [runtime, compatibility, model, toolsMaxLoops, baseUrl, clientTimeoutMs, streamTimeoutMs, newApiKey, toolToggles]);

    // ===== actions =====
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
            setOkMsg(t.banners.saved);
            await load();
        } catch (e: any) {
            setError(e?.message || String(e));
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
            setOkMsg(t.banners.reloaded);
        } catch (e: any) {
            setError(e?.message || String(e));
        } finally {
            setReloading(false);
        }
    };

    const restoreDefaults = async () => {
        if (typeof window !== "undefined" && !window.confirm(t.confirm.restore)) return;
        setResetting(true);
        setError(null);
        setOkMsg(null);
        try {
            const res = await fetch("/admin/config/replace", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({}),
            });
            if (!res.ok) throw new Error(await res.text());
            setOkMsg(t.banners.restored);
            await load();
        } catch (e: any) {
            setError(e?.message || String(e));
        } finally {
            setResetting(false);
        }
    };

    const resetForm = () => {
        if (!runtime || !effective) return;
        setCompatibility(runtime.compatibility ?? effective.compatibility ?? "OPENAI");
        setModel(runtime.model ?? effective.model ?? "");
        setToolsMaxLoops(Number(runtime.toolsMaxLoops ?? effective.toolsMaxLoops ?? 2));
        setBaseUrl(runtime.baseUrl ?? effective.baseUrl ?? "");
        setNewApiKey("");
        setApiKeyMasked(runtime.apiKeyMasked ?? effective.apiKeyMasked ?? null);
        setClientTimeoutMs(runtime.clientTimeoutMs ?? effective.clientTimeoutMs ?? "");
        setStreamTimeoutMs(runtime.streamTimeoutMs ?? effective.streamTimeoutMs ?? "");
        setToolToggles(runtime.toolToggles ?? {});
    };

    // toggles helpers
    const addToggle = () => {
        const name = prompt(lang === "zh" ? "输入工具名（function name）" : "Tool name (function name)");
        if (!name) return;
        setToolToggles((prev) => ({ ...prev, [name]: true }));
    };
    const removeToggle = (k: string) => {
        const next = { ...(toolToggles || {}) };
        delete next[k];
        setToolToggles(next);
    };

    // ===== UI =====
    return (
        <div className="min-h-screen w-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
            {/* Header */}
            <header className="sticky top-0 z-10 border-b bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:border-slate-800 dark:bg-slate-900/80 dark:supports-[backdrop-filter]:bg-slate-900/60">
                <div className="mx-auto max-w-6xl px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="h-9 w-9 rounded-2xl bg-gradient-to-tr from-blue-500 to-indigo-500 text-white grid place-items-center shadow-sm">
                            <Wrench size={18} />
                        </div>
                        <div>
                            <h1 className="text-lg font-semibold leading-tight">{t.title}</h1>
                            <p className="text-xs text-slate-500 dark:text-slate-400">{t.subtitle}</p>
                        </div>
                    </div>
                    <div className="flex items-center gap-2">
                        {/* Language toggle */}
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

                        <button
                            onClick={reload}
                            disabled={reloading}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                            title={t.tooltips.reload}
                        >
                            <RefreshCw size={16} /> {reloading ? t.actions.reloading : t.actions.reload}
                        </button>
                        <button
                            onClick={load}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                            title={t.tooltips.refresh}
                        >
                            <Rocket size={16} /> {t.actions.refresh}
                        </button>
                    </div>
                </div>
            </header>

            {/* Body */}
            <main className="mx-auto max-w-6xl px-4 py-6">
                {/* 状态条 */}
                <div className="mb-4 space-y-2">
                    {loading && (<Banner icon={<RefreshCw className="animate-spin" size={16} />} text={t.banners.loading} color="slate" />)}
                    {error && <Banner icon={<ShieldCheck size={16} />} text={error} color="red" />}
                    {okMsg && <Banner icon={<Wand2 size={16} />} text={okMsg} color="green" />}
                </div>

                {/* Snapshots */}
                {effective && (
                    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="mb-6 grid gap-4 md:grid-cols-2">
                        <Card title={t.sections.snapshots.effective}>
                            <Snap k="compatibility" v={effective.compatibility} />
                            <Snap k="model" v={effective.model} />
                            <Snap k="toolsMaxLoops" v={String(effective.toolsMaxLoops)} />
                            <Snap k="clientTimeoutMs" v={String(effective.clientTimeoutMs ?? "-")} />
                            <Snap k="streamTimeoutMs" v={String(effective.streamTimeoutMs ?? "-")} />
                            <Snap k="baseUrl" v={effective.baseUrl ?? "-"} />
                            <Snap k="apiKeyMasked" v={effective.apiKeyMasked ?? "-"} />
                        </Card>
                        <Card title={t.sections.snapshots.runtime}>
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

                {/* Form */}
                <motion.div
                    initial={{ opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900"
                >
                    <Section title={t.sections.basics}>
                        <div className="grid gap-4 md:grid-cols-3">
                            <Field label={t.fields.compatibility}>
                                <select
                                    value={compatibility}
                                    onChange={(e) => setCompatibility(e.target.value)}
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                >
                                    <option value="OPENAI">OPENAI</option>
                                    <option value="OLLAMA">OLLAMA</option>
                                </select>
                            </Field>
                            <Field label={t.fields.model}>
                                <input
                                    value={model}
                                    onChange={(e) => setModel(e.target.value)}
                                    placeholder={t.placeholders.model}
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                />
                            </Field>
                            <Field label={t.fields.loops}>
                                <input
                                    type="number"
                                    min={0}
                                    value={toolsMaxLoops}
                                    onChange={(e) => setToolsMaxLoops(Number(e.target.value))}
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                            </Field>
                        </div>
                    </Section>

                    <Section title={t.sections.network}>
                        <div className="grid gap-4 md:grid-cols-3">
                            <Field label={t.fields.baseUrl}>
                                <div className="relative">
                                    <input
                                        value={baseUrl}
                                        onChange={(e) => setBaseUrl(e.target.value)}
                                        className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                    />
                                    <Link className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                </div>
                            </Field>
                            <Field label={t.fields.newKey}>
                                <div className="relative">
                                    <input
                                        value={newApiKey}
                                        onChange={(e) => setNewApiKey(e.target.value)}
                                        placeholder={apiKeyMasked ? (lang === "zh" ? locales.zh.placeholders.keyMask(apiKeyMasked) : locales.en.placeholders.keyMask(apiKeyMasked)) : (lang === "zh" ? locales.zh.placeholders.keyUnset : locales.en.placeholders.keyUnset)}
                                        className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                    />
                                    <KeyRound className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                </div>
                            </Field>
                            <div className="grid grid-cols-2 gap-4">
                                <Field label={t.fields.clientTimeout}>
                                    <div className="relative">
                                        <input
                                            type="number"
                                            value={clientTimeoutMs as any}
                                            onChange={(e) => setClientTimeoutMs(e.target.value === "" ? "" : Number(e.target.value))}
                                            className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                        />
                                        <Timer className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                    </div>
                                </Field>
                                <Field label={t.fields.streamTimeout}>
                                    <div className="relative">
                                        <input
                                            type="number"
                                            value={streamTimeoutMs as any}
                                            onChange={(e) => setStreamTimeoutMs(e.target.value === "" ? "" : Number(e.target.value))}
                                            className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                        />
                                        <Timer className="absolute right-2 top-2.5 text-slate-400" size={18} />
                                    </div>
                                </Field>
                            </div>
                        </div>
                    </Section>

                    <Section title={t.sections.toggles}>
                        <div className="space-y-2">
                            <div className="text-xs text-slate-500 dark:text-slate-400">{t.sections.defaultOn}</div>
                            {availableTools.length === 0 ? (
                                <div className="text-sm text-slate-500 dark:text-slate-400">{t.sections.none}</div>
                            ) : (
                                <div className="flex flex-wrap gap-2">
                                    {availableTools.map((name) => {
                                        const checked = toolToggles[name] !== undefined ? !!toolToggles[name] : true;
                                        return (
                                            <label key={name} className={`flex items-center gap-2 rounded-full border px-3 py-1 text-sm cursor-pointer select-none ${checked ? 'bg-blue-50 border-blue-300 text-blue-700 dark:bg-blue-900/30 dark:border-blue-800 dark:text-blue-200' : 'bg-white border-slate-300 text-slate-700 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-200'}`}>
                                                <input
                                                    type="checkbox"
                                                    className="accent-blue-600 dark:accent-blue-400"
                                                    checked={checked}
                                                    onChange={(e) => setToolToggles({ ...toolToggles, [name]: e.target.checked })}
                                                />
                                                <span className="font-mono">{name}</span>
                                            </label>
                                        );
                                    })}
                                </div>
                            )}
                        </div>
                    </Section>

                    {/* Actions (sticky on desktop) */}
                    <div className="mt-5 md:sticky md:bottom-4 md:z-10 md:backdrop-blur md:bg-white/70 md:dark:bg-slate-900/70 md:rounded-2xl md:border md:border-slate-200 md:dark:border-slate-800 md:p-3 flex flex-wrap items-center gap-3">
                        <button
                            onClick={save}
                            disabled={saving || !diffPayload}
                            className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium ${
                                saving || !diffPayload
                                    ? "bg-slate-300 text-white dark:bg-slate-700"
                                    : "bg-blue-600 text-white hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400"
                            }`}
                        >
                            <Save size={16} /> {saving ? t.actions.saving : t.actions.save}
                        </button>
                        <button
                            onClick={resetForm}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                        >
                            <RefreshCw size={16} /> {t.actions.revert}
                        </button>
                        <button
                            onClick={restoreDefaults}
                            disabled={resetting}
                            className="inline-flex items-center gap-2 rounded-xl border border-red-300 bg-white px-4 py-2 text-sm text-red-700 hover:bg-red-50 dark:border-red-700 dark:bg-slate-800 dark:text-red-300 dark:hover:bg-red-900/30 disabled:opacity-60"
                        >
                            <Trash2 size={16} /> {resetting ? t.actions.restoring : t.actions.restore}
                        </button>
                        <button
                            onClick={() => setShowDiff(!showDiff)}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                        >
                            {showDiff ? <EyeOff size={16} /> : <Eye size={16} />} {showDiff ? t.actions.diffClose : t.actions.diffOpen}
                        </button>
                        {diffPayload && (
                            <span className="text-xs text-slate-500 dark:text-slate-400 self-center">
                {t.actions.willSubmit}: {Object.keys(diffPayload).join(", ") || (lang === "zh" ? "<空>" : "<none>")}
              </span>
                        )}
                    </div>

                    {showDiff && (
                        <motion.pre
                            initial={{ opacity: 0 }}
                            animate={{ opacity: 1 }}
                            className="mt-4 rounded-xl bg-slate-900 text-slate-100 p-4 overflow-auto text-xs dark:bg-black"
                        >
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

function Card({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div className="rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">{title}</div>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3 text-sm">{children}</div>
        </div>
    );
}

function Snap({ k, v }: { k: string; v: any }) {
    return (
        <div className="rounded-xl border bg-slate-50 p-2 dark:border-slate-700 dark:bg-slate-800">
            <div className="text-[11px] uppercase tracking-wide text-slate-400 dark:text-slate-400">{k}</div>
            <div className="font-medium break-words">{String(v ?? "-")}</div>
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
            {icon}
            <span>{text}</span>
        </div>
    );
}
