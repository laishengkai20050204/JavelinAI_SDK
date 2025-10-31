package com.example.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class ConfigStore {
    private final ObjectMapper om;
    private Path dir()  { return Paths.get("config"); }
    private Path file() { return dir().resolve("runtime-config.json"); }
    /** Load persisted runtime config if present; returns null when absent or unreadable. */
    public RuntimeConfig loadOrNull() {
        try {
            Path f = file();
            if (!Files.exists(f)) return null;
            return om.readValue(f.toFile(), RuntimeConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void save(RuntimeConfig cfg) throws IOException {
        Files.createDirectories(dir());
        Path tmp = dir().resolve("runtime-config.json.tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cfg);

        Path f = file();
        if (Files.exists(f)) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(f, dir().resolve("runtime-config.json.bak-" + ts), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
