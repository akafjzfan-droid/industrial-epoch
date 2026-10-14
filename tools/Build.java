import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Offline, fixed-input build. Run with the pinned JDK's source launcher. */
public final class Build {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path engine = Path.of(args.length > 0 ? args[0] :
            "libs/desktop.jar").toAbsolutePath();
        String expected = "f3a66564b1fbf45a1c0e50d7499c2283";
        if (!hash(engine).equals(expected)) throw new IllegalStateException("Engine fingerprint mismatch");
        Path build = Files.createTempDirectory(Files.createDirectories(root.resolve("build")), "compile-");
        Path classes = Files.createDirectory(build.resolve("classes"));
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac").toString(),
            "-encoding", "UTF-8", "--release", "17", "-g", "-cp", engine.toString(), "-d", classes.toString()));
        try (var paths = Files.walk(root.resolve("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString()));
        }
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (exit != 0) throw new IllegalStateException("javac exit=" + exit);
        SortedMap<String, Path> entries = new TreeMap<>();
        collect(classes, entries);
        collect(root.resolve("src/main/resources"), entries);
        Path output = Files.createDirectories(root.resolve("dist")).resolve("blackstart-0.5-test.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (var entry : entries.entrySet()) {
                byte[] data = Files.readAllBytes(entry.getValue());
                CRC32 crc = new CRC32(); crc.update(data);
                ZipEntry ze = new ZipEntry(entry.getKey());
                ze.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                ze.setMethod(ZipEntry.STORED); ze.setSize(data.length); ze.setCrc(crc.getValue());
                zip.putNextEntry(ze); zip.write(data); zip.closeEntry();
            }
        }
        String result = "BUILD_OK entries=" + entries.size() + " md5=" + hash(output);
        System.out.println(result);
        Files.createDirectories(root.resolve("evidence"));
        Files.writeString(root.resolve("evidence/build-latest.txt"), result + "\njava=" +
            System.getProperty("java.runtime.version") + "\nvendor=" + System.getProperty("java.vendor") +
            "\nos=" + System.getProperty("os.name") + "\nlocale=" + Locale.getDefault() +
            "\nengine=" + engine + "\nengineMD5=" + expected + "\n", StandardCharsets.UTF_8);
    }
    private static void collect(Path base, Map<String, Path> entries) throws IOException {
        try (var paths = Files.walk(base)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = base.relativize(path).toString().replace('\\', '/');
                if (entries.put(name, path) != null) throw new IOException("Duplicate jar entry: " + name);
            }
        }
    }
    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
