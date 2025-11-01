import { Link, NavLink, Outlet } from "react-router-dom";

export default function AdminRoot() {
    return (
        <div className="min-h-screen flex bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
            {/* Sidebar */}
            <aside className="w-60 border-r bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-800 p-4 space-y-2">
                <div className="text-lg font-semibold">Admin Console</div>
                <nav className="space-y-1 text-sm">
                    <NavLink
                        to="/admin/runtime"
                        className={({ isActive }) =>
                            `block px-2 py-1 rounded ${
                                isActive
                                    ? "bg-blue-600 text-white"
                                    : "hover:bg-slate-100 dark:hover:bg-slate-800"
                            }`
                        }
                    >
                        运行时配置 Runtime
                    </NavLink>
                    <NavLink
                        to="/admin/replay"
                        className={({ isActive }) =>
                            `block px-2 py-1 rounded ${
                                isActive
                                    ? "bg-blue-600 text-white"
                                    : "hover:bg-slate-100 dark:hover:bg-slate-800"
                            }`
                        }
                    >
                        回放中心 Replay
                    </NavLink>
                </nav>
                <Link to="/" className="text-xs text-slate-500">
                    ← 返回首页
                </Link>
            </aside>

            {/* Content */}
            <main className="flex-1 p-4">
                <Outlet />
            </main>
        </div>
    );
}
