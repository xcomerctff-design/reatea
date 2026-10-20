package net.reagen.verify;

import javax.tools.*;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Компиляция сгенерированного кода через javax.tools.JavaCompiler (встроен в JDK).
 * ВАЖНО: Forge API недоступен офлайн, поэтому компилируем ТОЛЬКО синтаксис и структуру —
 * подставляем минимальные стабы классов MC/Forge из stubs/, чтобы javac проверил семантику.
 */
public class JavacCheck {

    /** @return текст ошибки компиляции или null если код компилируется (со стабами). */
    public static String tryCompile(String code, String name) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return null; // нет JDK (только JRE) — пропускаем
        try {
            Path dir = Files.createTempDirectory("reagen-verify");
            Path pkg = dir.resolve("net/reagen/procedures");
            Files.createDirectories(pkg);
            Path src = pkg.resolve(cap(name) + "Procedure.java");
            Files.writeString(src, code);
            // соберём список стабов
            Path stubs = Path.of(System.getProperty("reagen.stubs", "java-backend/reagen/stubs"));
            StringWriter err = new StringWriter();
            StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
            List<File> files = new java.util.ArrayList<>(List.of(src.toFile()));
            if (Files.isDirectory(stubs)) {
                try (var walk = Files.walk(stubs)) {
                    walk.filter(f -> f.toString().endsWith(".java")).forEach(f -> files.add(f.toFile()));
                }
            }
            var units = fm.getJavaFileObjectsFromFiles(files);
            List<String> opts = List.of("-d", dir.toString(), "-proc:none", "-nowarn");
            boolean ok = compiler.getTask(err, fm, null, opts, null, units).call();
            fm.close();
            String out = err.toString();
            if (!ok) return out.replaceAll("\\s+", " ").trim();
            return null;
        } catch (Exception e) {
            return "internal error: " + e.getMessage();
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
