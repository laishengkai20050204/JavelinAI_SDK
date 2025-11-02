// src/components/TypeBadge.tsx

export function TypeBadge({ type }: { type?: string }) {
    const t = (type || "other").toLowerCase();
    const map: Record<string, string> = {
        message:
            "bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-900/20 dark:text-blue-200 dark:border-blue-800",
        decision:
            "bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-900/20 dark:text-amber-200 dark:border-amber-800",
        tool:
            "bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-900/20 dark:text-emerald-200 dark:border-emerald-800",
        started:
            "bg-slate-50 text-slate-700 border-slate-200 dark:bg-slate-900/20 dark:text-slate-200 dark:border-slate-800",
        finished:
            "bg-slate-50 text-slate-700 border-slate-200 dark:bg-slate-900/20 dark:text-slate-200 dark:border-slate-800",
        other:
            "bg-purple-50 text-purple-700 border-purple-200 dark:bg-purple-900/20 dark:text-purple-200 dark:border-purple-800",
    };
    const cls =
        map[t] ||
        "bg-slate-50 text-slate-700 border-slate-200 dark:bg-slate-900/20 dark:text-slate-200 dark:border-slate-800";
    const text = ["message", "decision", "tool", "started", "finished"].includes(t) ? t : "other";
    return (
        <span className={`rounded-full border px-2 py-[2px] text-[11px] font-medium ${cls}`}>
      {text}
    </span>
    );
}
