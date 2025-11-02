// src/lib/markdown.ts
import DOMPurify from "dompurify";
import { marked } from "marked";
import { markedHighlight } from "marked-highlight";
import hljs from "highlight.js";

// 启用 GFM 与软换行
marked.setOptions({ gfm: true, breaks: true });

// 代码高亮：优先显式语言，否则自动检测
marked.use(
  markedHighlight({
    langPrefix: "hljs language-",
    highlight(code, lang) {
      if (lang && hljs.getLanguage(lang)) {
        try { return hljs.highlight(code, { language: lang }).value; } catch {}
      }
      try { return hljs.highlightAuto(code).value; } catch { return code; }
    },
  })
);

// 反转义：把字面量 \n / \r\n / \t / \uXXXX 变成真实字符
export function unescapeText(s: string): string {
  if (typeof s !== "string") return String(s ?? "");
  try {
    // 用 JSON 解码一次，修复 \n、\t、\uXXXX 等
    return JSON.parse(`"${s.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`);
  } catch {
    // 兜底：常见转义
    return s.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
  }
}

export function markdownToSafeHtml(md: string): string {
  // 先反转义，再交给 marked，并用 DOMPurify 做 XSS 清理
  const plain = unescapeText(md ?? "");
  const raw = marked.parse(plain);
  return DOMPurify.sanitize(String(raw));
}

