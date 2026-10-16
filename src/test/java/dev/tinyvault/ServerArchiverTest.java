package dev.tinyvault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerArchiverTest {
    @TempDir
    Path root;

    @Test
    void createsWholeServerZipWithoutIncludingPluginDataOrItsOwnArchive() throws Exception {
        Path worlds = Files.createDirectories(root.resolve("worlds/main"));
        Files.writeString(worlds.resolve("level.dat"), "world data");
        Path pluginData = Files.createDirectories(root.resolve("plugins/TinyVault/tmp"));
        Files.writeString(root.resolve("plugins/TinyVault/config.yml"), "password: secret");
        Path archive = pluginData.resolve("backup.zip");

        new ServerArchiver(root, root.resolve("plugins/TinyVault")).createArchive(archive);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertTrue(zip.getEntry("worlds/main/level.dat") != null);
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("TinyVault")));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("backup.zip")));
            assertEquals("world data", new String(zip.getInputStream(zip.getEntry("worlds/main/level.dat")).readAllBytes()));
        }
    }
}