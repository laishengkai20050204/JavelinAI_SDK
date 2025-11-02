// src/components/JsonViewer.tsx
import DOMPurify from "dompurify";
import hljs from "highlight.js";
type JVProps = { data: any; label?: string; defaultOpen?: boolean; level?: number };

export function JsonViewer({ data, label, defaultOpen = false, level = 0 }: JVProps) {
    const isObj = typeof data === "object" && data !== null;
    const isArr = Array.isArray(data);

    if (!isObj) {
        if (typeof data === 'string' && /[\r\n]/.test(data)) {
            let html = '';
            try {
                html = hljs.highlightAuto(data).value;
            } catch {
                html = data.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            }
            const safe = DOMPurify.sanitize(html);
            return (
                <pre className="rounded p-2 whitespace-pre-wrap text-[12px] overflow-x-auto">
                    <code className="hljs" dangerouslySetInnerHTML={{ __html: safe }} />
                </pre>
            );
        }
        return (
            <code className="rounded bg-slate-800/70 px-1 py-0.5 text-[12px] text-emerald-200">
                {String(data)}
            </code>
        );
    }

    const keys = isArr ? data.map((_: any, i: number) => String(i)) : Object.keys(data);
    const size = isArr ? data.length : keys.length;
    const summary = label ?? (isArr ? `Array(${size})` : `Object(${size})`);

    return (
        <details open={level < 1 ? defaultOpen : false} className="my-1">
            <summary className="cursor-pointer select-none text-[12px] text-slate-400">
                {summary}
            </summary>
            <div className="ml-3 mt-1 border-l border-slate-700 pl-3">
                {isArr
                    ? data.map((v: any, i: number) => (
                        <div key={i} className="mb-1">
                            <div className="text-[12px] text-slate-500">[{i}]</div>
                            <JsonViewer data={v} level={level + 1} />
                        </div>
                    ))
                    : keys.map((k) => (
                        <div key={k} className="mb-1">
                            <div className="text-[12px] text-slate-500">{k}</div>
                            <JsonViewer data={(data as any)[k]} level={level + 1} />
                        </div>
                    ))}
            </div>
        </details>
    );
}
