package org.sqlgen.codegen;

import org.sqlgen.ast.Procedure;

/**
 * Генератор кода: Procedure AST -> текст CREATE FUNCTION / CREATE PROCEDURE.
 */
public interface CodeGenerator {
    String generate(Procedure procedure);

    static CodeGenerator forDialect(Procedure.Dialect d) {
        return switch (d) {
            case POSTGRESQL -> new PostgresGenerator();
            case MYSQL      -> new MysqlGenerator();
        };
    }
}
