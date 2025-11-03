import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
  Save, RefreshCw, Wand2, Rocket, ShieldCheck, KeyRound, Link as LinkIcon, Timer,
  Wrench, Eye, EyeOff, Trash2, Languages
} from "lucide-react";

type Lang = "zh" | "en";

type RuntimeCfg = {
  compatibility?: string;
  model?: string;
  toolsMaxLoops?: number;
  toolToggles?: Record<string, boolean>;
  baseUrl?: string;
  apiKeyMasked?: string | null;
  clientTimeoutMs?: number | null;
  streamTimeoutMs?: number | null;
};

type ConfigResp = {
  runtime: RuntimeCfg;
  effective: RuntimeCfg & { compatibility: string };
  availableTools?: string[];
};

const I18N = {
  zh: {
    title: "Javelin 配置控制台",
    subtitle: "运行时覆盖 · 生效配置 · 安全",
    actions: {
      reload: "重载",
      reloading: "正在重载...",
      refresh: "刷新",
      save: "保存",
      saving: "正在保存...",
      revert: "撤销未保存",
      restore: "恢复默认",
      restoring: "正在恢复...",
      diffOpen: "查看待提交 Diff",
      diffClose: "收起 Diff",
      willSubmit: "将提交",
      effectiveOpen: "查看生效配置",
      effectiveClose: "收起生效配置",
    },
    banners: {
      loading: "正在加载配置",
      saved: "已保存配置",
      reloaded: "已触发重载",
      restored: "已恢复为默认配置",
    },
    sections: {
      snapshots: { effective: "生效配置 (effective)", runtime: "运行时覆盖 (runtime)" },
      basics: "基础设置",
      network: "网络 & 超时（可选覆盖）",
      toggles: "工具开关 (toolToggles)",
      none: "（尚未声明任何开关）",
      defaultOn: "未声明的工具默认启用（true）",
    },
    fields: {
      compatibility: "兼容模式 (compatibility)",
      model: "模型 (model)",
      loops: "最大工具循环次数 (toolsMaxLoops)",
      baseUrl: "Base URL（覆盖）",
      newKey: "新的 API Key（旧值已隐藏）",
      clientTimeout: "clientTimeoutMs",
      streamTimeout: "streamTimeoutMs",
    },
    tooltips: { reload: "广播一次重载", refresh: "刷新配置" },
    placeholders: {
      model: "qwen2:7b / gpt-4o-mini / ...",
      keyUnset: "未设置",
      keyMask: (m: string) => `当前：${m}`,
    },
    on: "开", off: "关",
    confirm: { restore: "恢复默认？这将清空所有运行时覆盖。" },
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
      effectiveOpen: "Show Effective",
      effectiveClose: "Hide Effective",
    },
    banners: {
      loading: "Loading configuration",
      saved: "Configuration saved",
      reloaded: "Reload triggered",
      restored: "Restored to defaults",
    },
    sections: {
      snapshots: { effective: "Effective", runtime: "Runtime Overrides" },
      basics: "Basics",
      network: "Network & Timeouts (optional overrides)",
      toggles: "Tool Toggles (toolToggles)",
      none: "(No explicit toggles yet)",
      defaultOn: "Unspecified tools default to enabled (true)",
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
    tooltips: { reload: "Broadcast a reload", refresh: "Refresh config" },
    placeholders: {
      model: "qwen2:7b / gpt-4o-mini / ...",
      keyUnset: "Not set",
      keyMask: (m: string) => `Current: ${m}`,
    },
    on: "On", off: "Off",
    confirm: { restore: "Restore defaults? This will clear all runtime overrides." },
  },
} as const;

export default function AdminConfigConsole() {
  const [lang, setLang] = useState<Lang>(() =>
    typeof navigator !== "undefined" && navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en"
  );
  const t = I18N[lang];

  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [okMsg, setOkMsg] = useState<string | null>(null);

  const [runtime, setRuntime] = useState<RuntimeCfg | null>(null);
  const [effective, setEffective] = useState<RuntimeCfg | null>(null);
  const [availableTools, setAvailableTools] = useState<string[]>([]);

  const [compatibility, setCompatibility] = useState<string>("OPENAI");
  const [model, setModel] = useState<string>("");
  const [toolsMaxLoops, setToolsMaxLoops] = useState<number>(10);
  const [baseUrl, setBaseUrl] = useState<string>("");
  const [newApiKey, setNewApiKey] = useState<string>("");
  const [apiKeyMasked, setApiKeyMasked] = useState<string | null>(null);
  const [clientTimeoutMs, setClientTimeoutMs] = useState<number | "">("");
  const [streamTimeoutMs, setStreamTimeoutMs] = useState<number | "">("");
  const [toolToggles, setToolToggles] = useState<Record<string, boolean>>({});
  const [showDiff, setShowDiff] = useState<boolean>(false);
  const [showEffectiveSnap, setShowEffectiveSnap] = useState<boolean>(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    setOkMsg(null);
    try {
      const res = await fetch("/admin/config", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error(await res.text());
      const data: ConfigResp = await res.json();
      const r = data.runtime ?? {};
      const e = data.effective ?? ({} as any);
      const tools: string[] = Array.isArray(data.availableTools) ? data.availableTools : [];
      setRuntime(r); setEffective(e); setAvailableTools(tools);
      setCompatibility((r.compatibility ?? e.compatibility ?? "OPENAI") as string);
      setModel(r.model ?? e.model ?? "");
      setToolsMaxLoops(Number(r.toolsMaxLoops ?? e.toolsMaxLoops ?? 10));
      setBaseUrl(r.baseUrl ?? e.baseUrl ?? "");
      setApiKeyMasked(r.apiKeyMasked ?? e.apiKeyMasked ?? null);
      setClientTimeoutMs((r.clientTimeoutMs ?? e.clientTimeoutMs ?? "") as any);
      setStreamTimeoutMs((r.streamTimeoutMs ?? e.streamTimeoutMs ?? "") as any);
      setToolToggles(r.toolToggles ?? {});
      setNewApiKey("");
    } catch (e: any) {
      setError(e?.message || String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const toolNames = useMemo(() => {
    const s = new Set<string>(availableTools || []);
    Object.keys(toolToggles || {}).forEach((k) => s.add(k));
    return Array.from(s).sort((a, b) => a.localeCompare(b));
  }, [availableTools, toolToggles]);

  const diffPayload = useMemo(() => {
    if (!runtime) return null as any;
    const payload: any = {};
    const push = (k: string, v: any, cur: any) => {
      if (v === "") return;
      if (JSON.stringify(v) !== JSON.stringify(cur)) payload[k] = v;
    };
    push("compatibility", compatibility || undefined, runtime.compatibility ?? undefined);
    push("model", model || undefined, runtime.model ?? undefined);
    push("toolsMaxLoops",
      Number.isFinite(Number(toolsMaxLoops)) ? Number(toolsMaxLoops) : undefined,
      runtime.toolsMaxLoops ?? undefined
    );
    push("baseUrl", baseUrl || undefined, runtime.baseUrl ?? undefined);
    push("clientTimeoutMs",
      clientTimeoutMs === "" ? undefined : Number(clientTimeoutMs),
      runtime.clientTimeoutMs ?? undefined
    );
    push("streamTimeoutMs",
      streamTimeoutMs === "" ? undefined : Number(streamTimeoutMs),
      runtime.streamTimeoutMs ?? undefined
    );
    const normalized = Object.fromEntries(
      Object.entries(toolToggles || {}).filter(([, v]) => v !== true)
    );
    if (JSON.stringify(normalized) !== JSON.stringify(runtime.toolToggles ?? {})) {
      payload.toolToggles = normalized;
    }
    if (newApiKey && newApiKey.trim().length > 0) payload.apiKey = newApiKey.trim();
    return payload;
  }, [runtime, compatibility, model, toolsMaxLoops, baseUrl, clientTimeoutMs, streamTimeoutMs, newApiKey, toolToggles]);

  const isDiffEmpty = useMemo(() => !diffPayload || Object.keys(diffPayload as any).length === 0, [diffPayload]);

  const save = async () => {
    if (!diffPayload || Object.keys(diffPayload as any).length === 0) return;
    setSaving(true); setError(null); setOkMsg(null);
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
    setReloading(true); setError(null); setOkMsg(null);
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
    setResetting(true); setError(null); setOkMsg(null);
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
    setToolsMaxLoops(Number(runtime.toolsMaxLoops ?? effective.toolsMaxLoops ?? 10));
    setBaseUrl(runtime.baseUrl ?? effective.baseUrl ?? "");
    setNewApiKey("");
    setApiKeyMasked(runtime.apiKeyMasked ?? effective.apiKeyMasked ?? null);
    setClientTimeoutMs(runtime.clientTimeoutMs ?? effective.clientTimeoutMs ?? "");
    setStreamTimeoutMs(runtime.streamTimeoutMs ?? effective.streamTimeoutMs ?? "");
    setToolToggles(runtime.toolToggles ?? {});
  };

  return (
    <div className="min-h-screen w-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      {/* Header */}
      <header className="sticky top-0 z-10 border-b bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:border-slate-800 dark:bg-slate-900/80 dark:supports-[backdrop-filter]:bg-slate-900/60">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <div className="flex items-center gap-3">
            <div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-tr from-blue-500 to-indigo-500 text-white shadow-sm">
              <Wrench size={18} />
            </div>
            <div>
              <h1 className="text-lg font-semibold leading-tight">{t.title}</h1>
              <p className="text-xs text-slate-500 dark:text-slate-400">{t.subtitle}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <div className="inline-flex items-center rounded-xl border border-slate-300 bg-white p-1 text-sm dark:border-slate-700 dark:bg-slate-800">
              <button onClick={() => setLang("zh")} className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "zh" ? "bg-slate-200 dark:bg-slate-700" : ""}`} aria-pressed={lang === "zh"}>
                <Languages size={14} /> 中文
              </button>
              <button onClick={() => setLang("en")} className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "en" ? "bg-slate-200 dark:bg-slate-700" : ""}`} aria-pressed={lang === "en"}>
                EN
              </button>
            </div>
            <button onClick={reload} disabled={reloading} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700" title={t.tooltips.reload}>
              <RefreshCw size={16} /> {reloading ? t.actions.reloading : t.actions.reload}
            </button>
            <button onClick={load} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700" title={t.tooltips.refresh}>
              <Rocket size={16} /> {t.actions.refresh}
            </button>
          </div>
        </div>
      </header>

      {/* Body */}
      <main className="mx-auto max-w-6xl px-4 py-6">
        {/* banners */}
        <div className="mb-4 space-y-2">
          {loading && (<Banner icon={<RefreshCw className="animate-spin" size={16} />} text={t.banners.loading} color="slate" />)}
          {error && <Banner icon={<ShieldCheck size={16} />} text={error} color="red" />}
          {okMsg && <Banner icon={<Wand2 size={16} />} text={okMsg} color="green" />}
        </div>

        {/* Snapshots */}
          {effective && (
            <>
              <div className="mb-2 flex justify-end">
                <button
                  onClick={() => setShowEffectiveSnap((v) => !v)}
                  className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                >
                  {showEffectiveSnap ? <EyeOff size={16} /> : <Eye size={16} />}
                  {showEffectiveSnap ? t.actions.effectiveClose : t.actions.effectiveOpen}
                </button>
              </div>
              <motion.div
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                className="mb-6 grid gap-4 md:grid-cols-1"
              >
                {showEffectiveSnap ? (
                  <Card title={t.sections.snapshots.effective}>
                    <Snap k="compatibility" v={effective.compatibility} />
                    <Snap k="model" v={effective.model} />
                    <div className="md:col-span-2">
                      <Snap k="baseUrl" v={effective.baseUrl ?? "-"} />
                    </div>
                    <Snap k="apiKeyMasked" v={effective.apiKeyMasked ?? "-"} />
                    <Snap k="toolsMaxLoops" v={String(effective.toolsMaxLoops)} />
                    <Snap k="clientTimeoutMs" v={String(effective.clientTimeoutMs ?? "-")} />
                    <Snap k="streamTimeoutMs" v={String(effective.streamTimeoutMs ?? "-")} />
                  </Card>
                ) : (
                  <Card title={t.sections.snapshots.runtime}>
                    <Snap k="compatibility" v={runtime?.compatibility ?? "-"} />
                    <Snap k="model" v={runtime?.model ?? "-"} />
                    <div className="md:col-span-2">
                      <Snap k="baseUrl" v={runtime?.baseUrl ?? "-"} />
                    </div>
                    <Snap k="apiKeyMasked" v={runtime?.apiKeyMasked ?? "-"} />
                    <Snap k="toolsMaxLoops" v={String(runtime?.toolsMaxLoops ?? "-")} />
                    <Snap k="clientTimeoutMs" v={String(runtime?.clientTimeoutMs ?? "-")} />
                    <Snap k="streamTimeoutMs" v={String(runtime?.streamTimeoutMs ?? "-")} />
                  </Card>
                )}
              </motion.div>
            </>
          )}

        {/* Form */}
        <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <Section title={t.sections.basics}>
            <div className="grid gap-4 md:grid-cols-3">
              <Field label={t.fields.compatibility}>
                <select value={compatibility} onChange={(e) => setCompatibility(e.target.value)} className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100">
                  <option value="OPENAI">OPENAI</option>
                  <option value="OLLAMA">OLLAMA</option>
                </select>
              </Field>
              <Field label={t.fields.model}>
                <input value={model} onChange={(e) => setModel(e.target.value)} placeholder={t.placeholders.model} className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400" />
              </Field>
              <Field label={t.fields.loops}>
                <input type="number" min={0} value={toolsMaxLoops} onChange={(e) => setToolsMaxLoops(Number(e.target.value))} className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
              </Field>
            </div>
          </Section>

          <Section title={t.sections.network}>
            <div className="grid gap-4 md:grid-cols-3">
              <Field label={t.fields.baseUrl}>
                <div className="relative">
                  <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
                  <LinkIcon className="absolute right-2 top-2.5 text-slate-400" size={18} />
                </div>
              </Field>
              <Field label={t.fields.newKey}>
                <div className="relative">
                  <input value={newApiKey} onChange={(e) => setNewApiKey(e.target.value)} placeholder={ apiKeyMasked ? t.placeholders.keyMask(apiKeyMasked) : t.placeholders.keyUnset } className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400" />
                  <KeyRound className="absolute right-2 top-2.5 text-slate-400" size={18} />
                </div>
              </Field>
              <div className="grid grid-cols-2 gap-4">
                <Field label={t.fields.clientTimeout}>
                  <div className="relative">
                    <input type="number" value={clientTimeoutMs as any} onChange={(e) => setClientTimeoutMs(e.target.value === "" ? "" : Number(e.target.value))} className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
                    <Timer className="absolute right-2 top-2.5 text-slate-400" size={18} />
                  </div>
                </Field>
                <Field label={t.fields.streamTimeout}>
                  <div className="relative">
                    <input type="number" value={streamTimeoutMs as any} onChange={(e) => setStreamTimeoutMs(e.target.value === "" ? "" : Number(e.target.value))} className="w-full rounded-xl border border-slate-300 bg-white p-2 pr-9 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
                    <Timer className="absolute right-2 top-2.5 text-slate-400" size={18} />
                  </div>
                </Field>
              </div>
            </div>
          </Section>

          <Section title={t.sections.toggles}>
            <div className="space-y-3">
              <div className="text-xs text-slate-500 dark:text-slate-400">{t.sections.defaultOn}</div>
              {toolNames.length === 0 ? (
                <div className="text-sm text-slate-500 dark:text-slate-400">{t.sections.none}</div>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {toolNames.map((name) => {
                    const enabled = toolToggles[name] !== false;
                    return (
                      <label key={name} className={`flex select-none items-center gap-2 rounded-full border px-3 py-1 text-sm cursor-pointer ${enabled ? "bg-green-50 border-green-300 text-green-700 dark:bg-emerald-900/30 dark:border-emerald-800 dark:text-emerald-200" : "bg-white border-slate-300 text-slate-700 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-200"}`}
                        title={name}>
                        <input type="checkbox" className="accent-blue-600 dark:accent-blue-400" checked={enabled}
                          onChange={(e) => {
                            const nextChecked = e.target.checked;
                            setToolToggles((prev) => {
                              const next = { ...(prev || {}) } as Record<string, boolean>;
                              if (nextChecked) delete next[name]; else next[name] = false;
                              return next;
                            });
                          }} />
                        <span className="font-mono">{name}</span>
                        <span className="text-xs opacity-70">{enabled ? t.on : t.off}</span>
                      </label>
                    );
                  })}
                </div>
              )}
            </div>
          </Section>

          {/* Actions */}
          <div className="mt-5 flex flex-wrap items-center gap-3 md:sticky md:bottom-4 md:z-10 md:rounded-2xl md:border md:border-slate-200 md:bg-slate-50/80 md:p-3 md:backdrop-blur md:supports-[backdrop-filter]:bg-slate-50/60 transition-colors dark:md:border-slate-800 dark:md:bg-slate-900/70 dark:md:supports-[backdrop-filter]:bg-slate-900/60">
            <button onClick={save} disabled={saving || isDiffEmpty} className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium ${saving || isDiffEmpty ? "bg-slate-300 text-white dark:bg-slate-700" : "bg-blue-600 text-white hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400"}`}>
              <Save size={16} /> {saving ? t.actions.saving : t.actions.save}
            </button>
            <button onClick={resetForm} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
              <RefreshCw size={16} /> {t.actions.revert}
            </button>
            <button onClick={restoreDefaults} disabled={resetting} className="inline-flex items-center gap-2 rounded-xl border border-red-300 bg-white px-4 py-2 text-sm text-red-700 hover:bg-red-50 dark:border-red-700 dark:bg-slate-800 dark:text-red-300 dark:hover:bg-red-900/30 disabled:opacity-60">
              <Trash2 size={16} /> {resetting ? t.actions.restoring : t.actions.restore}
            </button>
            <button onClick={() => setShowDiff(!showDiff)} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
              {showDiff ? <EyeOff size={16} /> : <Eye size={16} />} {showDiff ? t.actions.diffClose : t.actions.diffOpen}
            </button>
            {diffPayload && (
              <span className="self-center text-xs text-slate-500 dark:text-slate-400">
                {t.actions.willSubmit}: {Object.keys(diffPayload).join(", ") || (lang === "zh" ? "<无>" : "<none>")}
              </span>
            )}
          </div>

          {showDiff && (
            <motion.pre initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="mt-4 overflow-auto rounded-xl bg-slate-900 p-4 text-xs text-slate-100 dark:bg-black">
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
      <div className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">{children}</div>
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
