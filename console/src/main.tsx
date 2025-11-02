import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import AdminRoot from "./pages/AdminRoot";
import RuntimeConfigPage from "./pages/RuntimeConfigPage";
import ReplayCenterConsole from "./components/ReplayCenterConsole";
import "./index.css";
import "./styles/hljs.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
        <BrowserRouter basename={import.meta.env.BASE_URL}>
            <Routes>
                <Route path="/" element={<Navigate to="/admin/runtime" replace />} />
                <Route path="/admin" element={<AdminRoot />}>
                    <Route index element={<Navigate to="/admin/runtime" replace />} />
                    <Route path="runtime" element={<RuntimeConfigPage />} />
                    <Route path="replay" element={<ReplayCenterConsole />} />
                </Route>
                <Route path="*" element={<Navigate to="/admin/runtime" replace />} />
            </Routes>
        </BrowserRouter>
    </React.StrictMode>
);
