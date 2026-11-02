package fr.polytech.mnia.exploration.prob;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** A private physical copy of a complete B specification tree for one ProB worker. */
public final class ModelWorkspace implements AutoCloseable {
    private final Path directory;
    private final Path machinePath;
    private final boolean preserveOnClose;
    private boolean closed;

    private ModelWorkspace(Path directory, Path machinePath, boolean preserveOnClose) {
        this.directory = directory;
        this.machinePath = machinePath;
        this.preserveOnClose = preserveOnClose;
    }

    /**
     * Copies the entire source tree to preserve relative includes and definitions.
     * Files outside this tree (e.g., absolute-path dependencies) are not copied.
     */
    public static ModelWorkspace create(Path sourceRoot, Path relativeMachine, boolean preserveOnClose)
            throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(relativeMachine, "relativeMachine");
        Path source = sourceRoot.toAbsolutePath().normalize();
        Path relative = relativeMachine.normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
            throw new IllegalArgumentException("Machine path must stay inside the source tree: " + relativeMachine);
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source.resolve(relative), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing source root or B machine: " + source.resolve(relative));
        }
        Path destination = Files.createTempDirectory("rllib4prob-worker-");
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new IOException("Symbolic link not allowed in model tree: " + dir);
                    }
                    Files.createDirectories(destination.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) {
                        throw new IOException("Unsupported or linked file in model tree: " + file);
                    }
                    Files.copy(file, destination.resolve(source.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return new ModelWorkspace(destination, destination.resolve(relative), preserveOnClose);
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTree(destination);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Path directory() {
        return directory;
    }

    public Path machinePath() {
        return machinePath;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (!preserveOnClose) {
            deleteTree(directory);
        }
        closed = true;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
