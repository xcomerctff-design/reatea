package net.reagen.blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Реестр блоков процедур (аналог палитры Blockly в MCreator).
 * Единый источник истины: описание блоков для UI-палитры редактора,
 * статической проверки типов верификатором и симулятора.
 */
public final class BlockDef {

    public enum Kind { STATEMENT, EXPRESSION }

    /** Порт блока: имя + тип данных (number|string|logic|itemstack|list|entity|any) + shadow-блок значения по умолчанию. */
    public static final class Port {
        public final String name, type, shadow;
        public Port(String name, String type, String shadow) { this.name = name; this.type = type; this.shadow = shadow; }
    }

    public final String type;               // "mc_spawnentity"
    public final Kind kind;
    public final String category;           // game, logic, variables...
    public final String outputType;         // для EXPRESSION: тип значения
    public final List<Port> inputs = new ArrayList<>();
    public final List<String> fields = new ArrayList<>();
    public final int branches;              // 0, 1 (тело), 2+ (if/else)
    public final boolean needsPlayer;       // требует контекст игрока (_ent instanceof Player)

    private BlockDef(String type, Kind kind, String category, String outputType, int branches, boolean needsPlayer) {
        this.type = type; this.kind = kind; this.category = category;
        this.outputType = outputType; this.branches = branches; this.needsPlayer = needsPlayer;
    }

    private BlockDef in(String name, String type, String shadow) { inputs.add(new Port(name, type, shadow)); return this; }
    private BlockDef fl(String name) { fields.add(name); return this; }

    public static Builder define(String type, Kind kind, String category) {
        return new Builder(type, kind, category);
    }

    public static final class Builder {
        private final BlockDef d;
        private String out = null; private int br = 0; private boolean plr = false;
        private final List<Port> ins = new ArrayList<>(); private final List<String> flds = new ArrayList<>();
        Builder(String type, Kind kind, String category) { d = new BlockDef(type, kind, category, null, 0, false); }
        public Builder output(String t) { this.out = t; return this; }
        public Builder branchCount(int n) { this.br = n; return this; }
        public Builder playerRequired() { this.plr = true; return this; }
        public Builder input(String name, String type, String shadow) { ins.add(new Port(name, type, shadow)); return this; }
        public Builder field(String name) { flds.add(name); return this; }
        public BlockDef build() {
            BlockDef r = new BlockDef(d.type, d.kind, d.category, out, br, plr);
            r.inputs.addAll(ins); r.fields.addAll(flds); return r;
        }
    }

    // ---------- реестр ----------

    private static final List<BlockDef> ALL = new ArrayList<>();
    public static List<BlockDef> all() { return ALL; }
    public static BlockDef byType(String type) { for (BlockDef b : ALL) if (b.type.equals(type)) return b; return null; }

    private static BlockDef reg(BlockDef b) { ALL.add(b); return b; }

    static {
        // выражения (Blockly core)
        reg(define("math_number", Kind.EXPRESSION, "math").output("number").field("NUM").build());
        reg(define("text", Kind.EXPRESSION, "text").output("string").field("TEXT").build());
        reg(define("logic_boolean", Kind.EXPRESSION, "logic").output("logic").field("BOOL").build());
        reg(define("logic_compare", Kind.EXPRESSION, "logic").output("logic")
                .input("A", "any", "math_number").input("B", "any", "math_number").field("OP").build());
        reg(define("logic_operation", Kind.EXPRESSION, "logic").output("logic")
                .input("A", "logic", "logic_boolean").input("B", "logic", "logic_boolean").field("OP").build());
        reg(define("not", Kind.EXPRESSION, "logic").output("logic")
                .input("BOOL", "logic", "logic_boolean").build());
        reg(define("math_arithmetic", Kind.EXPRESSION, "math").output("number")
                .input("A", "number", "math_number").input("B", "number", "math_number").field("OP").build());
        reg(define("variables_get", Kind.EXPRESSION, "variables").output("any").field("VAR").build());
        // игровые выражения
        reg(define("coord_x", Kind.EXPRESSION, "game").output("number").build());
        reg(define("entity_getpos", Kind.EXPRESSION, "game").output("number").field("ENTITY").build());
        reg(define("entity_gety", Kind.EXPRESSION, "game").output("number").field("ENTITY").build());
        reg(define("entity_getz", Kind.EXPRESSION, "game").output("number").field("ENTITY").build());
        reg(define("itemstack_count", Kind.EXPRESSION, "game").output("number").field("ITEMSTACK").build());
        reg(define("mc_getitemfromslot", Kind.EXPRESSION, "game").output("itemstack").playerRequired()
                .input("SLOT", "number", "math_number").build());
        reg(define("itemstack_make", Kind.EXPRESSION, "game").output("itemstack")
                .input("ITEM", "string", "text").input("COUNT", "number", "math_number").build());
        // statements
        reg(define("controls_if", Kind.STATEMENT, "logic").branchCount(1).build());
        reg(define("controls_forEach", Kind.STATEMENT, "loops").branchCount(1)
                .input("LIST", "list", null).field("VAR").build());
        reg(define("procedures_setvar", Kind.STATEMENT, "variables").field("VAR")
                .input("VALUE", "any", null).build());
        reg(define("return_logic", Kind.STATEMENT, "control")
                .input("RETURN", "logic", "logic_boolean").build());
        reg(define("return_number", Kind.STATEMENT, "control")
                .input("RETURN", "number", "math_number").build());
        reg(define("return_string", Kind.STATEMENT, "control")
                .input("RETURN", "string", "text").build());
        reg(define("procedures_callreturn", Kind.STATEMENT, "control").field("NAME").build());
        reg(define("mc_sendmessage", Kind.STATEMENT, "game")
                .input("MESSAGE", "string", "text").build());
        reg(define("mc_spawnentity", Kind.STATEMENT, "game").field("TYPE")
                .input("X", "number", "math_number").input("Y", "number", "math_number").input("Z", "number", "math_number").build());
        reg(define("mc_give_item_to_player", Kind.STATEMENT, "game").playerRequired()
                .input("ITEMSTACK", "itemstack", null).build());
    }

    /** JSON описания всех блоков — для палитры редактора (GET /blocks). */
    public static String toJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ALL.size(); i++) {
            BlockDef b = ALL.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"").append(b.type)
              .append("\",\"kind\":\"").append(b.kind == Kind.STATEMENT ? "statement" : "expression")
              .append("\",\"category\":\"").append(b.category).append("\"");
            if (b.outputType != null) sb.append(",\"output\":\"").append(b.outputType).append("\"");
            if (!b.fields.isEmpty()) {
                sb.append(",\"fields\":[");
                for (int f = 0; f < b.fields.size(); f++) { if (f > 0) sb.append(','); sb.append('"').append(b.fields.get(f)).append('"'); }
                sb.append(']');
            }
            if (!b.inputs.isEmpty()) {
                sb.append(",\"inputs\":[");
                for (int p = 0; p < b.inputs.size(); p++) {
                    Port pt = b.inputs.get(p);
                    if (p > 0) sb.append(',');
                    sb.append("{\"name\":\"").append(pt.name).append("\",\"type\":\"").append(pt.type).append('"');
                    if (pt.shadow != null) sb.append(",\"shadow\":\"").append(pt.shadow).append('"');
                    sb.append('}');
                }
                sb.append(']');
            }
            if (b.branches > 0) sb.append(",\"branches\":").append(b.branches);
            if (b.needsPlayer) sb.append(",\"needsPlayer\":true");
            sb.append('}');
        }
        return sb.append(']').toString();
    }
}
