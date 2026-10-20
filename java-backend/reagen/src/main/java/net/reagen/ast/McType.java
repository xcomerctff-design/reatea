package net.reagen.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/** Типы процедурных переменных (аналог MCreatorTypes: NUMBER/STRING/LOGIC/...). */
public enum McType {
    NUMBER("double"), STRING("String"), LOGIC("boolean"),
    ITEMSTACK("ItemStack"), ENTITY("Entity"), WORLD("Level");

    public final String javaType;
    McType(String javaType) { this.javaType = javaType; }

    /** Приведение выражения к Java-значению этого типа (аналог ProcedureUtil.castToDeclaredType в MCreator). */
    public String castExpr(String expr) {
        return switch (this) {
            case NUMBER -> "(double) (" + expr + ")";
            case STRING -> "String.valueOf(" + expr + ")";
            case LOGIC -> "(boolean) (" + expr + ")";
            default -> "(" + expr + ")";
        };
    }

    public static McType from(String s) {
        if (s == null) throw new IllegalArgumentException("Missing type declaration");
        return switch (s.toLowerCase()) {
            case "number", "int", "double" -> NUMBER;
            case "string", "text" -> STRING;
            case "logic", "bool", "boolean" -> LOGIC;
            case "itemstack", "item" -> ITEMSTACK;
            case "entity" -> ENTITY;
            case "world", "level" -> WORLD;
            default -> throw new IllegalArgumentException("Unknown type: " + s);
        };
    }

    /** Присваивание совместимо ли значение типа src переменной типа dst. */
    public boolean assignableFrom(McType src) {
        if (src == this) return true;
        if (this == ENTITY && src == WORLD) return false;
        if (this == NUMBER && src == LOGIC) return false;
        if (this == LOGIC && src == NUMBER) return false;
        if (this == STRING) return true; // String.valueOf термостойкий
        return false;
    }
}
