package dev.tinyvault;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams the whole server directory (worlds, player data, plugins,
 * configuration, everything else) into a single ZIP file. TinyVault's own data
 * folder is skipped so temporary archives, the state file and the credential
 * config never end up inside a backup.
 */
final class ServerArchiver {
    private final Path serverRoot;
    private final Path excludedDir;

    ServerArchiver(Path serverRoot, Path pluginDataFolder) {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.excludedDir = pluginDataFolder.toAbsolutePath().normalize();
    }

    void createArchive(Path destination) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            Files.walkFileTree(serverRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.toAbsolutePath().normalize().equals(excludedDir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = entryName(dir);
                    if (!name.isEmpty()) {
                        zip.putNextEntry(new ZipEntry(name + "/"));
                        zip.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    zip.putNextEntry(new ZipEntry(entryName(file)));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private String entryName(Path path) {
        Path relative = serverRoot.relativize(path.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/');
    }
}
