package net.reagen.codegen;

import net.reagen.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Генератор кода процедуры для Minecraft Forge 1.20.1 (MDK 47.x).
 * Аналог ProcedureGenerator в MCreator: собирает .java-файл класса <Name>Procedure.java.
 */
public class ProcedureGenerator {

    public String generate(Procedure p) {
        Scope scope = p.buildScope();
        ElementVisitor ev = new ElementVisitor();
        ElementVisitor.FragmentCollector sink = new ElementVisitor.FragmentCollector();
        List<String> body = ev.traverse(p, p.byId(p.rootId), scope, sink);

        StringBuilder sb = new StringBuilder();
        sb.append("package net.reagen.procedures;\n\n");
        // Стандартные импорты MCreator-style процедур
        List<String> imports = new ArrayList<>(List.of(
            "java.util.ArrayList",
            "java.util.List",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.level.Level",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.core.BlockPos",
            "net.minecraft.world.entity.player.Player"
        ));
        for (String i : sink.imports) if (!imports.contains(i)) imports.add(i);
        imports.sort(String::compareTo);
        for (String i : imports) sb.append("import ").append(i).append(";\n");
        sb.append("\n");

        // Объявления локальных переменных
        for (var e : p.locals.entrySet()) {
            sb.append("// local var ").append(e.getKey()).append(" : ").append(e.getValue().javaType).append('\n');
        }

        String cls = capitalize(p.name) + "Procedure";
        sb.append("public class ").append(cls).append(" {\n\n");

        // Поля-переменные (MCreator хранит locals как поля при необходимости — здесь упрощённо как параметры execute)
        StringBuilder params = new StringBuilder("Level world, double x, double y, double z, Entity _ent");
        for (var a : p.args.entrySet()) params.append(", ").append(a.getValue().javaType).append(' ').append(a.getKey());

        sb.append("\tpublic static ").append(returnJava(p)).append(" execute(").append(params).append(") {\n");
        sb.append("\t\tfinal Level _world = world;\n");
        // MCreator-style: локальные _x/_y/_z с подчёркиванием (параметры называются x,y,z)
        sb.append("\t\tfinal double _x = x;\n\t\tfinal double _y = y;\n\t\tfinal double _z = z;\n");
        sb.append("\t\tfinal Entity _entity = _ent == null ? null : _ent;\n");
        // Локальные переменные
        for (var e : p.locals.entrySet()) {
            sb.append("\t\t").append(e.getValue().javaType).append(' ').append(e.getKey()).append(" = ").append(defaultVal(e.getValue())).append(";\n");
        }
        sb.append("\n");
        for (String line : body) sb.append("\t\t").append(line).append('\n');
        if (p.returnType != McType.LOGIC || !endsWithReturn(body)) {
            sb.append("\t\treturn ").append(defaultReturn(p)).append(";\n");
        }
        sb.append("\t}\n\n");

        // Overload без entity (для вызовов из мира/тайлов) — как в MCreator.
        // ВАЖНО: если у процедуры есть пользовательские аргументы, world-only overload
        // не может их вывести — генерируем его только для процедур без args.
        if (p.args.isEmpty()) {
            sb.append("\tpublic static ").append(returnJava(p)).append(" execute(Level world, double x, double y, double z) {\n");
            sb.append("\t\treturn execute(world, x, y, z, null);\n\t}\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private boolean endsWithReturn(List<String> body) {
        return !body.isEmpty() && body.get(body.size() - 1).startsWith("return ");
    }

    private String returnJava(Procedure p) {
        return switch (p.returnType) {
            case NUMBER -> "double";
            case STRING -> "String";
            case LOGIC -> "boolean";
            default -> "void";
        };
    }

    private String defaultReturn(Procedure p) {
        return switch (p.returnType) {
            case NUMBER -> "0";
            case STRING -> "\"\"";
            case LOGIC -> "false";
            default -> "null";
        };
    }

    private String defaultVal(McType t) {
        return switch (t) {
            case NUMBER -> "0";
            case STRING -> "\"\"";
            case LOGIC -> "false";
            case ITEMSTACK -> "ItemStack.EMPTY";
            default -> "null";
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
