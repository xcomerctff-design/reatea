package net.reagen.verify;

import net.reagen.blocks.BlockDef;
import net.reagen.codegen.ProcedureGenerator;
import net.reagen.parser.ProcedureGraphParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * CLI-верификатор (аналог VerifyMain из прошлой итерации, тот же протокол для Rust-моста):
 *   java -cp reagen.jar net.reagen.verify.VerifyCli --daemon
 * Протокол: запрос = JSON процедуры + маркер "\n%%EOT%%\n"; ответ = VerifyReport JSON + %%EOT%%.
 * Одиночный режим: один запрос из stdin без флага --daemon.
 * Режимы: --blocks — выдать JSON реестра блоков (палитра редактора);
 *         --generate — выдать сгенерированный Java-код вместо отчёта проверки.
 */
public class VerifyCli {
    private static final String EOT = "%%EOT%%";

    public static void main(String[] args) throws Exception {
        boolean daemon = false, blocks = false, generate = false;
        for (String a : args) switch (a) {
            case "--daemon" -> daemon = true;
            case "--blocks" -> blocks = true;
            case "--generate" -> generate = true;
        }
        if (blocks) { System.out.println(BlockDef.toJson()); return; }
        if (daemon) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            loop:
            while (true) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.equals(EOT)) break;
                    sb.append(line).append('\n');
                }
                if (sb.length() == 0 && line == null) break; // EOF
                String resp = handle(sb.toString(), generate);
                System.out.println(resp);
                System.out.println(EOT);
                System.out.flush();
            }
        } else {
            String all = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            System.out.print(handle(all, generate));
        }
    }

    private static String handle(String json, boolean generate) {
        try {
            var p = new ProcedureGraphParser().parse(json);
            if (generate) {
                return "{\"java\":\"" + esc(new ProcedureGenerator().generate(p)) + "\"}";
            }
            var report = new ProcedureVerifier().verify(p);
            return report.toJson();
        } catch (Exception e) {
            return "{\"valid\":false,\"issues\":[{\"elementId\":\"root\",\"severity\":\"ERROR\",\"rule\":\"PARSE\",\"message\":\""
                    + esc(e.getMessage()) + "\"}]}";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
