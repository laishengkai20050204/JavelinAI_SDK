import typography from '@tailwindcss/typography';

export default {
    darkMode: 'media', // 跟随系统(浏览器)主题；若写过 'class' 请改回 'media'
    content: ["./index.html","./src/**/*.{ts,tsx}"],
    theme: { extend: {} },
    plugins: [typography],
}
