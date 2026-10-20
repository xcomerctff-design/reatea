package org.sqlgen;

import org.sqlgen.parser.ProcedureJsonParser;
import org.sqlgen.verify.SchemaModel;
import org.sqlgen.verify.Verifier;
import org.sqlgen.verify.VerifyReport;

/**
 * Консольный smoke-runner: компилируется без JUnit и позволяет проверить
 * работу верификатора «на месте»:  java -cp out org.sqlgen.SmokeRunner
 */
public final class SmokeRunner {

    private static int failures = 0;

    public static void main(String[] args) {
        String ddl = """
            CREATE TABLE customers (
                id BIGSERIAL PRIMARY KEY,
                email TEXT NOT NULL,
                balance NUMERIC(12,2)
            );
            CREATE TABLE orders (
                id BIGSERIAL PRIMARY KEY,
                customer_id BIGINT,
                amount NUMERIC(12,2)
            );
            """;
        SchemaModel schema = SchemaModel.fromDdl(ddl);
        Verifier v = new Verifier(schema);

        // позитивный кейс
        String good = """
            {"name":"get_orders","dialect":"postgresql",
             "params":[{"name":"p_customer_id","type":"BIGINT"}],
             "steps":[
               {"kind":"guard","id":"g1","line":2,
                "existsQuery":"SELECT 1 FROM customers c WHERE c.id = p_customer_id",
                "message":"customer not found"},
               {"kind":"return","id":"r1","line":6,
                "selectSql":"SELECT o.id, o.amount FROM orders o WHERE o.customer_id = p_customer_id"}
             ]}""";
        VerifyReport rg = v.verify(ProcedureJsonParser.parse(good));
        expect("valid procedure passes", rg.isValid());
        expect("generated SQL produced", rg.generatedSql != null && rg.generatedSql.contains("CREATE OR REPLACE FUNCTION get_orders"));

        // негативный кейс: несуществующая таблица и колонка
        String bad = """
            {"name":"x_proc","params":[],
             "steps":[{"kind":"sql","id":"s1","line":1,
                       "sql":"SELECT ghost_col FROM phantom_table WHERE a.b = 1"}]}""";
        VerifyReport rb = v.verify(ProcedureJsonParser.parse(bad));
        expect("invalid procedure fails", !rb.isValid());
        expect("table error detected", rb.issues.stream().anyMatch(i -> i.rule.equals("SCHEMA_TABLE")));
        expect("column error detected", rb.issues.stream().anyMatch(i -> i.rule.equals("SCHEMA_COLUMN") || i.rule.equals("SCHEMA_ALIAS")));

        // синтаксический кейс: разбаланс скобок
        String unbalanced = """
            {"name":"paren_bad","params":[],
             "steps":[{"kind":"sql","id":"s1","line":1,"sql":"SELECT id FROM orders o WHERE (o.amount > 5"}]}""";
        VerifyReport ru = v.verify(ProcedureJsonParser.parse(unbalanced));
        expect("unbalanced parens fail", !ru.isValid());

        // регрессия E2E-бага: Rust-мост присылает snake_case — парсер обязан его понимать
        String snake = """
            {"name":"get_orders_snake","dialect":"postgresql",
             "params":[{"name":"p_customer_id","type":"BIGINT"}],
             "steps":[
               {"kind":"guard","id":"g1","line":1,
                "exists_query":"SELECT 1 FROM customers c WHERE c.id = p_customer_id",
                "message":"customer not found"},
               {"kind":"return","id":"r1","line":2,
                "select_sql":"SELECT o.id, o.amount FROM orders o WHERE o.customer_id = p_customer_id"}
             ]}""";
        VerifyReport rs = v.verify(ProcedureJsonParser.parse(snake));
        expect("snake_case spec from Rust bridge passes", rs.isValid());

        System.out.println(failures == 0 ? "SMOKE OK" : "SMOKE FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void expect(String what, boolean cond) {
        System.out.println((cond ? "  [PASS] " : "  [FAIL] ") + what);
        if (!cond) failures++;
    }
}
