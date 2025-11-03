import React, { Suspense } from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate, useLocation } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
const StepOrchestratorPage = React.lazy(() => import("./pages/StepOrchestratorPage")); // ← 新增懒加载
import "./index.css";
import "./styles/hljs.css";

// 懒加载页面
const AdminRoot = React.lazy(() => import("./pages/AdminRoot"));
const RuntimeConfigPage = React.lazy(() => import("./pages/RuntimeConfigPage"));
const ReplayCenterConsole = React.lazy(() => import("./components/ReplayCenterConsole"));
const AuditConsolePage = React.lazy(() => import("./pages/AuditConsolePage"));

function PageTransition({ children }: { children: React.ReactNode }) {
    const location = useLocation();
    return (
        <AnimatePresence mode="wait">
            <motion.div
                key={location.pathname}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                transition={{ duration: 0.18, ease: "easeOut" }}
                className="h-full"
            >
                {children}
            </motion.div>
        </AnimatePresence>
    );
}

function RouterTree() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/admin/runtime" replace />} />
            <Route
                path="/admin"
                element={
                    <Suspense fallback={<div className="p-8 text-sm opacity-70">Loading admin…</div>}>
                        <AdminRoot />
                    </Suspense>
                }
            >
                <Route index element={<Navigate to="/admin/runtime" replace />} />

                <Route
                    path="runtime"
                    element={
                        <PageTransition>
                            <Suspense fallback={<div className="p-8 text-sm opacity-70">Loading runtime…</div>}>
                                <RuntimeConfigPage />
                            </Suspense>
                        </PageTransition>
                    }
                />

                <Route
                    path="replay"
                    element={
                        <PageTransition>
                            <Suspense fallback={<div className="p-8 text-sm opacity-70">Loading replay…</div>}>
                                <ReplayCenterConsole />
                            </Suspense>
                        </PageTransition>
                    }
                />

                <Route
                    path="audit"
                    element={
                        <PageTransition>
                            <Suspense fallback={<div className="p-8 text-sm opacity-70">Loading audit…</div>}>
                                <AuditConsolePage />
                            </Suspense>
                        </PageTransition>
                    }
                />

                {/* 新增：Orchestrator（M3） */}
                <Route
                    path="orchestrator"
                    element={
                        <PageTransition>
                            <Suspense fallback={<div className="p-8 text-sm opacity-70">Loading orchestrator…</div>}>
                                <StepOrchestratorPage />
                            </Suspense>
                        </PageTransition>
                    }
                />
            </Route>

            <Route path="*" element={<Navigate to="/admin/runtime" replace />} />
        </Routes>
    );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
        <BrowserRouter basename={import.meta.env.BASE_URL}>
            <RouterTree />
        </BrowserRouter>
    </React.StrictMode>
);
