package dev.qan4ur.faketab.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record VelocityConfig(String backend, int syncIntervalSeconds, int sampleSize, boolean addToCount) {

    static VelocityConfig load(Path dataDir) throws IOException {
        Path file = dataDir.resolve("config.properties");
        if (Files.notExists(file)) {
            Files.createDirectories(dataDir);
            try (InputStream in = VelocityConfig.class.getResourceAsStream("/config.properties")) {
                Files.copy(in, file);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new VelocityConfig(
                p.getProperty("backend", "lobby").trim(),
                Math.max(1, parseInt(p.getProperty("sync-interval-seconds"), 10)),
                Math.max(0, parseInt(p.getProperty("sample-size"), 12)),
                Boolean.parseBoolean(p.getProperty("add-to-count", "false").trim()));
    }

    private static int parseInt(String raw, int def) {
        try {
            return raw == null ? def : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
