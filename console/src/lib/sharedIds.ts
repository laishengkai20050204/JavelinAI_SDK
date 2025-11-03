// src/lib/sharedIds.ts
import * as React from "react";

type SharedIds = { userId?: string; conversationId?: string };

const STORAGE_KEY = "javelin.sharedIds";

let state: SharedIds = (() => {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as SharedIds;
  } catch {}
  return {};
})();

const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

function setState(patch: Partial<SharedIds>) {
  state = { ...state, ...patch };
  try {
    if (typeof window !== "undefined") {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    }
  } catch {}
  emit();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot() {
  return state;
}

/**
 * useSharedIds
 * - 读取/更新全局的 userId 与 conversationId
 * - 可提供每页的显示默认值（不会写回全局，直到用户编辑）
 */
export function useSharedIds(defaultUserId = "", defaultConversationId = "") {
  const snap = React.useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const rawUserId = snap.userId; // undefined 表示从未设置过
  const rawConversationId = snap.conversationId;

  const setUserId = React.useCallback((v: string) => setState({ userId: v }), []);
  const setConversationId = React.useCallback(
    (v: string) => setState({ conversationId: v }),
    []
  );

  // 仅在“未初始化（undefined）”时使用默认值；若用户输入过空串，则应显示为空而不是默认值
  const displayUserId = rawUserId === undefined ? defaultUserId : (rawUserId as string);
  const displayConversationId = rawConversationId === undefined ? defaultConversationId : (rawConversationId as string);

  return {
    userId: displayUserId,
    conversationId: displayConversationId,
    setUserId,
    setConversationId,
  };
}
