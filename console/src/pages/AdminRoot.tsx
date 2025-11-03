import React from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { motion } from "framer-motion";
import { Rocket, Wrench, History, Github, Menu, ShieldCheck } from "lucide-react"; // ← 新增 ShieldCheck

function useBreadcrumb() {
    const { pathname } = useLocation();
    const parts = pathname.split("/").filter(Boolean);
    const items: { name: string; href: string }[] = [];
    let acc = "";
    for (const p of parts) {
        acc += "/" + p;
        items.push({ name: p, href: acc });
    }
    return items;
}

export default function AdminRoot() {
    const crumbs = useBreadcrumb();
    const [open, setOpen] = React.useState(true);

    return (
        <div className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-900 dark:to-slate-950 text-slate-800 dark:text-slate-100">
            {/* 顶部色条 */}
            <div className="h-1 bg-gradient-to-r from-indigo-500 via-sky-500 to-emerald-500" />

            {/* Header */}
            <header className="sticky top-0 z-40 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:supports-[backdrop-filter]:bg-slate-900/60 border-b border-slate-200/60 dark:border-slate-800">
                <div className="mx-auto max-w-7xl px-3 sm:px-6 lg:px-8 h-14 flex items-center justify-between">
                    <button
                        className="p-2 rounded-xl hover:bg-black/5 dark:hover:bg-white/5"
                        onClick={() => setOpen((v) => !v)}
                        aria-label="Toggle sidebar"
                    >
                        <Menu className="w-5 h-5" />
                    </button>

                    <div className="flex items-center gap-3">
                        <Rocket className="w-5 h-5" />
                        <span className="font-semibold tracking-tight">Javelin Admin</span>
                    </div>

                    <div className="flex items-center gap-2">
                        <a
                            className="inline-flex items-center gap-2 px-3 py-1.5 rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-black/5 dark:hover:bg-white/5 text-sm"
                            href="https://github.com/laishengkai20050204/JavelinAI_SDK"
                            target="_blank"
                            rel="noreferrer"
                        >
                            <Github className="w-4 h-4" />
                            GitHub
                        </a>
                    </div>
                </div>
            </header>

            {/* 主体：Flex 布局（侧栏宽度动画，主区域自适应） */}
            <div className="mx-auto max-w-7xl px-3 sm:px-6 lg:px-8 py-6 flex gap-6">
                {/* Sidebar */}
                <motion.aside
                    initial={false}
                    animate={{ width: open ? 240 : 64 }}
                    className="shrink-0 overflow-hidden rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/60 dark:bg-slate-900/60 backdrop-blur"
                >
                    <nav className="p-2">
                        <SideLink to="/admin/runtime" icon={<Wrench className="w-4 h-4" />}>Runtime</SideLink>
                        <SideLink to="/admin/replay"  icon={<History className="w-4 h-4" />}>Replay</SideLink>
                        <SideLink to="/admin/audit"   icon={<ShieldCheck className="w-4 h-4" />}>Audit</SideLink> {/* ← 新增 */}
                        <SideLink to="/admin/orchestrator" icon={<Rocket className="w-4 h-4" />}>Orchestrator</SideLink>
                    </nav>
                </motion.aside>

                {/* Main */}
                <main className="flex-1 min-w-0">
                    {/* 面包屑 */}
                    <div className="mb-4 text-sm text-slate-500 flex flex-wrap items-center gap-2">
                        {crumbs.map((c, i) => (
                            <div key={c.href} className="flex items-center gap-2">
                                {i > 0 && <span className="opacity-60">/</span>}
                                <NavLink to={c.href} className="hover:underline capitalize">
                                    {c.name}
                                </NavLink>
                            </div>
                        ))}
                    </div>

                    {/* 内容卡片 */}
                    <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/70 backdrop-blur p-4 sm:p-6 shadow-sm">
                        <Outlet />
                    </div>

                    <footer className="py-10 text-xs opacity-60">
                        © {new Date().getFullYear()} JavelinAI — Built with React Router, Tailwind, Framer Motion.
                    </footer>
                </main>
            </div>
        </div>
    );
}

function SideLink({ to, icon, children }: { to: string; icon: React.ReactNode; children: React.ReactNode }) {
    return (
        <NavLink
            to={to}
            className={({ isActive }) =>
                `group flex items-center gap-3 rounded-xl px-3 py-2 text-sm font-medium transition-colors ${
                    isActive
                        ? "bg-gradient-to-r from-indigo-500/10 to-emerald-500/10 text-slate-900 dark:text-slate-100 border border-indigo-200/50 dark:border-indigo-800/40"
                        : "text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white hover:bg-black/5 dark:hover:bg-white/5"
                }`
            }
            end
        >
            <span className="shrink-0">{icon}</span>
            <span className="truncate">{children}</span>
        </NavLink>
    );
}
