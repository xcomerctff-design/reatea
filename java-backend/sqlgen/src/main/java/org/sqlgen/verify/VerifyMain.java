package org.sqlgen.verify;

import org.sqlgen.parser.ProcedureJsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * CLI-точка входа для вызова из Rust/Tauri как внешнего процесса (подход C, ADR-001).
 *
 * Протокол:
 *   запуск:  java -cp sqlgen.jar org.sqlgen.verify.VerifyMain [--schema path.ddl] [--daemon]
 *   запрос:  JSON спецификации процедуры в stdin (одна строка), ответ: отчёт-JSON + '\u0004' (EOT)
 *   в daemon-режиме процесс не завершается — редактор переиспользует его между проверками.
 */
public final class VerifyMain {

    public static void main(String[] args) throws Exception {
        String schemaPath = null;
        boolean daemon = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--schema" -> schemaPath = args[++i];
                case "--daemon" -> daemon = true;
                default -> System.err.println("unknown arg: " + args[i]);
            }
        }

        String ddl = schemaPath != null
                ? Files.readString(Path.of(schemaPath), StandardCharsets.UTF_8)
                : "";
        SchemaModel schema = SchemaModel.fromDdl(ddl);
        Verifier verifier = new Verifier(schema);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        do {
            String line = in.readLine();
            if (line == null) break;
            if (line.isBlank()) continue;
            String outJson;
            try {
                var proc = ProcedureJsonParser.parse(line);
                outJson = verifier.verify(proc).toJson();
            } catch (ProcedureJsonParser.ParseError pe) {
                outJson = "{\"valid\":false,\"errors\":[{\"stepIndex\":-1,\"line\":" + pe.line
                        + ",\"severity\":\"ERROR\",\"rule\":\"JSON\",\"message\":\""
                        + pe.getMessage().replace("\"", "'") + "\"}]}";
            } catch (Exception e) {
                outJson = "{\"valid\":false,\"errors\":[{\"stepIndex\":-1,\"line\":0,"
                        + "\"severity\":\"ERROR\",\"rule\":\"INTERNAL\",\"message\":\""
                        + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}]}";
            }
            System.out.println(outJson + "\u0004");     // EOT-разделитель ответов
            System.out.flush();
        } while (daemon);
    }
}
