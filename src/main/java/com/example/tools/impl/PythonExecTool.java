package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.PythonToolProperties;
import com.example.tools.AiTool;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@AiToolComponent
//@RequiredArgsConstructor
public class PythonExecTool implements AiTool {

    private final PythonToolProperties props;

    public PythonExecTool(PythonToolProperties props) {
        this.props = props;
        log.debug("PythonExecTool init. docker={}, image={}, denyNet={}, cmd={}, timeout={}",
                props.isUseDocker(), props.getDockerImage(), props.isDenyNetwork(),
                props.getPythonCmd(), props.getTimeout());
    }

    @Override
    public String name() { return "python_exec"; }

    @Override
    public String description() {
        return "Run short Python 3 code on the server and return stdout/stderr. Use for quick computation or parsing.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> propsNode = new LinkedHashMap<>();
        propsNode.put("code", Map.of("type","string","description","Python 3 code to run. Print results to stdout."));
        propsNode.put("stdin", Map.of("type","string","description","Optional stdin passed to the process."));
        propsNode.put("args", Map.of("type","array","items", Map.of("type","string"), "description","Optional argv for the script."));
        propsNode.put("timeout_ms", Map.of("type","integer","minimum",100,"maximum",600000,"default",15000));
        propsNode.put("files", Map.of(
                "type","array",
                "items", Map.of("type","object","properties", Map.of(
                        "path", Map.of("type","string","description","Relative path like data/in.txt"),
                        "content", Map.of("type","string","description","Text content")
                ), "required", List.of("path")),
                "description","Optional auxiliary text files to create before running."
        ));
        propsNode.put("return_files", Map.of("type","array","items", Map.of("type","string"),
                "description","Relative file paths to read back as text after execution."));
        propsNode.put("pip", Map.of("type","array","items", Map.of("type","string"),
                "description","Packages to pip install (ignored unless allowPip=true)."));

        schema.put("properties", propsNode);
        schema.put("required", List.of("code"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (!props.isEnabled()) {
            log.error("python_exec is disabled by config.");
            return ToolResult.error(null, name(), "tool is disabled by config");
        }

        String userId = asString(args.getOrDefault("userId", null));
        String convId = asString(args.getOrDefault("conversationId", null));
        String stepId = asString(args.getOrDefault("_stepId", null));
        if (userId != null) MDC.put("userId", userId);
        if (convId != null) MDC.put("conversationId", convId);
        if (stepId != null) MDC.put("stepId", stepId);

        long t0 = System.nanoTime();

        try {
            String code = asString(args.get("code"));
            if (code == null || code.isBlank()) {
                log.error("execute aborted: missing code");
                return ToolResult.error(null, name(), "code is required");
            }
            String stdin = asString(args.get("stdin"));
            List<String> argv = asStringList(args.get("args"));
            List<Map<String, Object>> files = asMapList(args.get("files"));
            List<String> returnFiles = asStringList(args.get("return_files"));
            List<String> pip = asStringList(args.get("pip"));
            int timeoutMs = clampTimeout(asInt(args.get("timeout_ms"), 15000));

            String codeHash = sha256Short(code);
            log.info("python_exec start. codeLen={}, codeHash={}, argvSize={}, files={}, returnFiles={}, pip={}, timeoutMs={}",
                    code.length(), codeHash, argv == null ? 0 : argv.size(),
                    files == null ? 0 : files.size(),
                    returnFiles == null ? 0 : returnFiles.size(),
                    pip == null ? 0 : pip.size(),
                    timeoutMs);

            File workDir;
            try {
                workDir = Files.createTempDirectory("pyexec-").toFile();
                log.debug("workDir created: {}", workDir.getAbsolutePath());
            } catch (IOException e) {
                log.error("failed to create tmp dir", e);
                return ToolResult.error(null, name(), "failed to create tmp dir: " + e.getMessage());
            }

            // �?main.py
            File script = new File(workDir, "main.py");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(script), StandardCharsets.UTF_8)) {
                w.write(code);
            } catch (IOException e) {
                log.error("failed to write main.py", e);
                return ToolResult.error(null, name(), "failed to write script: " + e.getMessage());
            }

            // 写入辅助文件（保持不变）
            if (files != null && !files.isEmpty()) {
                for (Map<String, Object> f : files) {
                    String rel = asString(f.get("path"));
                    String content = asString(f.get("content"));
                    if (rel == null || rel.isBlank()) continue;
                    File dst = new File(workDir, rel);
                    if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8)) {
                        w.write(content == null ? "" : content);
                    } catch (IOException e) {
                        log.error("failed to write file {}", rel, e);
                        return ToolResult.error(null, name(), "failed to write file " + rel + ": " + e.getMessage());
                    }
                }
                log.debug("aux files written: {}", files.size());
            }

            // pip（可选）
            if (pip != null && !pip.isEmpty()) {
                if (!props.isAllowPip()) {
                    log.error("pip requested but allowPip=false, ignore. requested={}", pip);
                } else {
                    log.info("pip installing: {}", pip);
                    // ⬇️ 改用跨平台的 pip 执行
                    ToolResult pipRes = runOnce(buildPipCmd(workDir, pip), null, workDir, timeoutMs);
                    if (!"SUCCESS".equals(pipRes.status())) {
                        log.error("pip failed: {}", pipRes.data());
                        return ToolResult.error(null, name(), "pip install failed: " + pipRes.data());
                    } else {
                        log.debug("pip done.");
                    }
                }
            }

            // 执行
            List<String> cmd = buildRunCmd(workDir);     // ⬅️ 将在下面重写
            if (argv != null && !argv.isEmpty()) cmd.addAll(argv);
            log.debug("exec cmd: {}", safeJoin(cmd));

            ToolResult run = runOnce(cmd, stdin, workDir, timeoutMs);

            long durMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            log.info("python_exec finished in {} ms", durMs);

            // ⬇️ ADD: “非零退出码”统一视为 ERROR，避免被去重器当�?SUCCESS 复用
            Map<String, Object> runMap = asMap(run.data());
            Map<String, Object> inner = asMap(runMap.getOrDefault("payload", runMap));
            Integer ec = asInt(inner.get("exitCode"), null);
            String so = asString(inner.get("stdout"));
            String se = asString(inner.get("stderr"));
            logAtOutputLevel(ec, so, se, props.getMaxOutputBytes());
            if (ec != null && ec != 0) {
                Map<String, Object> errPayload = new LinkedHashMap<>(inner);
                errPayload.put("durationMs", durMs);
                return ToolResult.error(null, name(), "python exit " + ec + (se == null || se.isBlank() ? "" : ("; stderr=" + abbreviate(se, 512))));
            }
            // ⬆️ ADD

            // 回读文件
            Map<String, Object> filesOut = new LinkedHashMap<>();
            if (returnFiles != null) {
                for (String p : returnFiles) {
                    File f = new File(workDir, p);
                    if (f.exists() && f.isFile()) {
                        try {
                            filesOut.put(p, Files.readString(f.toPath()));
                        } catch (IOException e) {
                            log.debug("read return file failed: {}", p, e);
                        }
                    } else {
                        log.debug("return file not found: {}", p);
                    }
                }
                if (!filesOut.isEmpty()) log.debug("return files read: {}", filesOut.keySet());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("durationMs", durMs);
            // ⬇️ 兼容 runOnce 返回结构：可能是 {payload:{...}} 或直接扁�?{...}
            if (!inner.isEmpty()) payload.putAll(inner);
            if (!filesOut.isEmpty()) payload.put("files", filesOut);

            return ToolResult.success(null, name(), false, Map.of("payload", payload));
        } finally {
            if (userId != null) MDC.remove("userId");
            if (convId != null) MDC.remove("conversationId");
            if (stepId != null) MDC.remove("stepId");
        }
    }


    // ------------------- 进程执行与限�?-------------------

    private ToolResult runOnce(List<String> cmd, String stdin, File workDir, int timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(false);

        // 🔧 新增：强制 UTF-8（Windows/Linux/macOS 通吃）
        Map<String, String> env = pb.environment();
        env.putIfAbsent("PYTHONIOENCODING", "utf-8");
        env.putIfAbsent("PYTHONUTF8", "1");     // Python 3.7+
        env.putIfAbsent("LANG", "C.UTF-8");     // *nix 常见
        env.putIfAbsent("LC_ALL", "C.UTF-8");

        try {
            Process p = pb.start();

            // stdin
            try (OutputStream os = p.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }

            ExecutorService es = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "pyexec-io");
                t.setDaemon(true);
                return t;
            });

            Future<byte[]> stdoutF = es.submit(() -> readLimited(p.getInputStream()));
            Future<byte[]> stderrF = es.submit(() -> readLimited(p.getErrorStream()));

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                es.shutdownNow();
                log.error("subprocess timeout: {} ms", timeoutMs);
                return ToolResult.error(null, name(), "timeout after " + timeoutMs + " ms");
            }

            int exit = p.exitValue();
            byte[] out = getFuture(stdoutF);
            byte[] err = getFuture(stderrF);
            es.shutdownNow();

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("exitCode", exit);
            res.put("stdout", new String(out, StandardCharsets.UTF_8));
            res.put("stderr", new String(err, StandardCharsets.UTF_8));
            res.put("truncated", Map.of(
                    "stdout", out.length >= props.getMaxOutputBytes(),
                    "stderr", err.length >= props.getMaxOutputBytes()
            ));

            if (exit == 0) {
                log.debug("subprocess exit=0, stdoutLen={}, stderrLen={}", out.length, err.length);
            } else {
                log.error("subprocess exit={}, stdoutLen={}, stderrLen={}", exit, out.length, err.length);
            }

            return ToolResult.success(null, name(), false, res);
        } catch (Exception e) {
            log.error("subprocess failed: {}", e.toString());
            return ToolResult.error(null, name(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private byte[] readLimited(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long limit = props.getMaxOutputBytes();
        int r;
        while ((r = is.read(buf)) != -1) {
            int toWrite = (int) Math.min(r, Math.max(0, limit - bos.size()));
            if (toWrite > 0) bos.write(buf, 0, toWrite);
            if (bos.size() >= limit) break;
        }
        return bos.toByteArray();
    }

    private static byte[] getFuture(Future<byte[]> f) {
        try { return f.get(5, TimeUnit.SECONDS); }
        catch (Exception ignore) { return new byte[0]; }
    }

    // ------------------- 命令构建 -------------------


    private int clampTimeout(int requestedMs) {
        long max = props.getTimeout().toMillis();
        if (requestedMs <= 0) return (int) Math.min(15000, max);
        return (int) Math.min(requestedMs, max);
    }

    // ------------------- 日志辅助 -------------------

    private static void logAtOutputLevel(Object exitCode, Object stdout, Object stderr, long limit) {
        int ec = -1;
        try { if (exitCode != null) ec = Integer.parseInt(String.valueOf(exitCode)); } catch (Exception ignore){}
        String so = stdout == null ? "" : String.valueOf(stdout);
        String se = stderr == null ? "" : String.valueOf(stderr);

        String soPreview = safePreview(so, 200);
        String sePreview = safePreview(se, 200);

        if (ec == 0) {
            log.debug("exit=0, stdoutPreview={}, stderrPreview={}", soPreview, sePreview);
        } else {
            log.error("exit={}, stdoutPreview={}, stderrPreview={}", ec, soPreview, sePreview);
        }
    }

    private static String safePreview(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...(+" + (s.length() - n) + " chars)";
    }

    private static String safeJoin(List<String> cmd) {
        return String.join(" ", cmd);
    }

    private static String sha256Short(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.substring(0, 16); // �?16 位做 preview
        } catch (Exception e) {
            return "na";
        }
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }

    private static int asInt(Object o, int dft) {
        try { return o == null ? dft : Integer.parseInt(String.valueOf(o)); }
        catch (Exception e) { return dft; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> l) {
            List<String> r = new ArrayList<>();
            for (Object x : l) r.add(String.valueOf(x));
            return r;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> asMapList(Object o) {
        if (o instanceof List<?> l) {
            List<Map<String,Object>> r = new ArrayList<>();
            for (Object x : l) if (x instanceof Map<?,?> m) r.add((Map<String, Object>) m);
            return r;
        }
        return null;
    }


    // ⬇️ ADD: 解析 python 命令（优先用配置；无配置时：Windows=py -3�?nix=python3�?
    private List<String> resolvePythonCmdTokens() {
        String raw = props.getPythonCmd(); // e.g. "python", "py -3", "C:\\Python311\\python.exe"
        if (raw == null || raw.isBlank()) {
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            raw = win ? "py -3" : "python3";
        }
        return Arrays.asList(raw.trim().split("\\s+"));
    }

    private List<String> buildRunCmd(File workDir) {
        List<String> cmd = new ArrayList<>(resolvePythonCmdTokens());
        cmd.addAll(List.of("-X", "utf8", "-u", "-B"));
        cmd.add("main.py");
        return cmd;
    }

    private List<String> buildPipCmd(File workDir, List<String> pkgs) {
        List<String> cmd = new ArrayList<>(resolvePythonCmdTokens());
        cmd.addAll(List.of("-X", "utf8"));
        cmd.add("-m");
        cmd.add("pip");
        cmd.add("install");
        cmd.addAll(pkgs);
        return cmd;
    }

    // ⬇️ 小工具方法（若你类里已有同名/功能方法，可忽略�?
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private Integer asInt(Object v, Integer dft) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dft : Integer.parseInt(String.valueOf(v)); } catch (Exception ignore) { return dft; }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, Math.max(0, max)) + "...";
    }

}
