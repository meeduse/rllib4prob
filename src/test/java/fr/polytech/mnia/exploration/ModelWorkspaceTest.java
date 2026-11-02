package fr.polytech.mnia.exploration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import fr.polytech.mnia.exploration.prob.ModelWorkspace;

/** Can be run with javac/java without downloading ProB. */
public final class ModelWorkspaceTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("workspace-test-source-");
        try {
            Path folder = Files.createDirectories(root.resolve("models"));
            Files.writeString(folder.resolve("IncFlip.mch"), "MACHINE IncFlip END");
            Files.writeString(folder.resolve("defs.def"), "DEFINITIONS foo == 1");
            Path dirA;
            Path dirB;
            try (ModelWorkspace a = ModelWorkspace.create(root, Path.of("models/IncFlip.mch"), false);
                 ModelWorkspace b = ModelWorkspace.create(root, Path.of("models/IncFlip.mch"), false)) {
                dirA = a.directory();
                dirB = b.directory();
                check(!dirA.equals(dirB), "Workspaces collide");
                check(!Files.isSameFile(a.machinePath(), b.machinePath()), "Machine file is shared");
                check(Arrays.equals(Files.readAllBytes(a.machinePath()), Files.readAllBytes(b.machinePath())),
                        "Machine copies differ");
                check(Files.exists(dirA.resolve("models/defs.def"))
                        && Files.exists(dirB.resolve("models/defs.def")), "Relative definition missing");
            }
            check(Files.notExists(dirA) && Files.notExists(dirB), "Workspace cleanup failed");
            boolean rejected = false;
            try {
                ModelWorkspace.create(root, Path.of("../outside.mch"), false);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "Path traversal was accepted");
            System.out.println("PASS: separate copies, relative dependencies, cleanup, path safety");
        } finally {
            Files.deleteIfExists(root.resolve("models/IncFlip.mch"));
            Files.deleteIfExists(root.resolve("models/defs.def"));
            Files.deleteIfExists(root.resolve("models"));
            Files.deleteIfExists(root);
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
