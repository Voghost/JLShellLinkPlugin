import java.io.OutputStream;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Exercises the JVM verifier against the exact shaded candidate archive. */
public final class VerifyPluginJar {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected candidate JAR");
        try (var jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName().toUpperCase(java.util.Locale.ROOT);
                if (name.startsWith("META-INF/") && name.substring(9).indexOf('/') < 0 &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
                         name.endsWith(".EC") || name.startsWith("META-INF/SIG-"))) {
                    throw new SecurityException("Dependency signature leaked into shaded plugin");
                }
                if (!entry.isDirectory()) {
                    try (var input = jar.getInputStream(entry)) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                }
            }
        }
        System.out.println("Exact shaded plugin JVM archive verification passed.");
    }
}
