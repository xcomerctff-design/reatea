package org.sqlgen.codegen;

import org.sqlgen.ast.Procedure;

/** MySQL генератор (CREATE PROCEDURE, DELIMITER-обёртка). */
public class MysqlGenerator implements CodeGenerator {

    @Override
    public String generate(Procedure p) {
        StringBuilder sb = new StringBuilder();
        sb.append("DELIMITER //\n");
        sb.append("CREATE PROCEDURE ").append(p.name).append("(");
        for (int i = 0; i < p.params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("IN ").append(p.params.get(i).name).append(' ').append(p.params.get(i).type);
        }
        sb.append(")\nBEGIN\n");

        for (Procedure.Step s : p.steps) {
            sb.append("    -- step: ").append(s.id).append('\n');
            if (s instanceof Procedure.SqlStep q) {
                sb.append(PostgresGenerator.indent(q.sql)).append(";\n\n");
            } else if (s instanceof Procedure.GuardStep g) {
                sb.append("    IF NOT EXISTS (\n").append(PostgresGenerator.indent(g.existsQuery))
                  .append("\n    ) THEN\n");
                sb.append("        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '")
                  .append(PostgresGenerator.esc(g.message)).append("';\n");
                sb.append("    END IF;\n\n");
            } else if (s instanceof Procedure.ReturnStep r) {
                sb.append(PostgresGenerator.indent(r.selectSql)).append(";\n\n");
            } else if (s instanceof Procedure.AssignStep a) {
                sb.append("    SET ").append(a.variable).append(" = (")
                  .append(a.expression).append(");\n\n");
            }
        }
        sb.append("END //\nDELIMITER ;\n");
        return sb.toString();
    }
}
