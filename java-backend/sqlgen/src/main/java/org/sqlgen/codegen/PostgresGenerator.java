package org.sqlgen.codegen;

import org.sqlgen.ast.Procedure;

/** PostgreSQL/plpgsql генератор. */
public class PostgresGenerator implements CodeGenerator {

    @Override
    public String generate(Procedure p) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION ").append(p.name).append("(");
        for (int i = 0; i < p.params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(p.params.get(i).name).append(' ').append(p.params.get(i).type);
        }
        sb.append(")\nRETURNS VOID\nLANGUAGE plpgsql\nAS $$\nBEGIN\n");

        for (Procedure.Step s : p.steps) {
            sb.append("    -- step: ").append(s.id).append('\n');
            if (s instanceof Procedure.SqlStep q) {
                sb.append(indent(q.sql)).append(";\n\n");
            } else if (s instanceof Procedure.GuardStep g) {
                sb.append("    IF NOT EXISTS (\n").append(indent(g.existsQuery)).append("\n    ) THEN\n");
                sb.append("        RAISE EXCEPTION '").append(esc(g.message)).append("';\n");
                sb.append("    END IF;\n\n");
            } else if (s instanceof Procedure.ReturnStep r) {
                sb.append("    RETURN QUERY\n").append(indent(r.selectSql)).append(";\n\n");
            } else if (s instanceof Procedure.AssignStep a) {
                sb.append("    ").append(a.variable).append(" := (")
                  .append(a.expression).append(");\n\n");
            }
        }
        sb.append("END;\n$$;\n");
        return sb.toString();
    }

    static String indent(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("(?m)^", "    ").stripTrailing();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
