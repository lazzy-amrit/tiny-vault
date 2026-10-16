package dev.tinyvault;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ConfigTest {
    @Test
    void parsesSafeBlompDefaultsAndPreservesExistingSettings() {
        InputStream stream = getClass().getResourceAsStream("/config.yml");
        Map<String, Object> config = new Yaml().load(stream);
        assertEquals("", config.get("username"));
        assertEquals("", config.get("password"));
        assertEquals("tinyvault", config.get("dir"));
        assertEquals(3, config.get("limit"));
        assertEquals("24h", config.get("timer"));
        assertEquals("Asia/Kolkata", config.get("timezone"));
        assertEquals("24", config.get("time-format"));
        assertEquals("", config.get("webhook-url"));
        assertEquals(false, config.get("extreme"));
        assertFalse(config.containsKey("filename-format"), "filename-format was removed by design");
        assertFalse(config.containsKey("api-key"), "Blomp needs no api-key");
        assertFalse(config.containsKey("endpoint"), "Blomp needs no endpoint");
        assertFalse(config.containsKey("bucket"), "Blomp needs no bucket");
    }
}