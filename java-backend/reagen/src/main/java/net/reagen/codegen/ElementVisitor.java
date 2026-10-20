package net.reagen.codegen;

import net.reagen.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Аналог ElementVisitor из MCreator: обходит граф процедуры и сопоставляет каждый
 * элемент (Blockly-блок) его Java-фрагменту. Возвращает список Fragments для генерации.
 */
public class ElementVisitor {

    /** Сгенерировать выражение (value-цепочку) из подключённого элемента, вернуть Java-код. */
    public String expression(Procedure p, ProcedureElement.Input input, Scope scope) {
        if (input == null) return "false";
        ProcedureElement el = input.connectedElementId == null ? null : p.byId(input.connectedElementId);
        if (el == null) { // shadow-значение по умолчанию
            return switch (input.shadowType == null ? "" : input.shadowType) {
                case "math_number" -> "0";
                case "text" -> "\"\"";
                case "logic_boolean" -> "false";
                default -> "null";
            };
        }
        return expressionOf(p, el, scope);
    }

    public String expressionOf(Procedure p, ProcedureElement el, Scope scope) {
        return switch (el.type) {
            case "math_number" -> el.field("NUM");
            case "text" -> quote(el.field("TEXT"));
            case "logic_boolean" -> el.field("BOOL");
            case "variables_get" -> varRef(scope, el.field("VAR"));
            case "entity_getpos", "coord_x" -> "(" + entVar(el) + ".getX())";
            case "entity_gety" -> "(" + entVar(el) + ".getY())";
            case "entity_getz" -> "(" + entVar(el) + ".getZ())";
            case "itemstack_count" -> "(" + isVar(el) + ".getCount())";
            case "player_gethealth" -> "(_ent instanceof Player __ph ? (double) __ph.getHealth() : 0.0)";
            case "player_issurvival" -> "(_ent instanceof Player __ps && __ps.isSurvival())";
            case "logic_compare" -> {
                String a = expression(p, el.input("A"), scope);
                String b = expression(p, el.input("B"), scope);
                yield switch (el.field("OP")) {
                    case "EQ" -> a + " == " + b;
                    case "NEQ" -> a + " != " + b;
                    case "LT" -> a + " < " + b;
                    case "LTE" -> a + " <= " + b;
                    case "GT" -> a + " > " + b;
                    case "GTE" -> a + " >= " + b;
                    default -> throw new RuntimeException("bad op " + el.field("OP"));
                };
            }
            case "math_arithmetic" -> {
                String a = expression(p, el.input("A"), scope);
                String b = expression(p, el.input("B"), scope);
                yield switch (el.field("OP")) {
                    case "ADD" -> "(" + a + " + " + b + ")";
                    case "MINUS" -> "(" + a + " - " + b + ")";
                    case "MULTIPLY" -> "(" + a + " * " + b + ")";
                    case "DIVIDE" -> "(" + a + " / " + b + ")";
                    default -> throw new RuntimeException("bad arith op");
                };
            }
            case "logic_operation" -> {
                String a = expression(p, el.input("A"), scope);
                String b = expression(p, el.input("B"), scope);
                yield "(" + a + ("AND".equals(el.field("OP")) ? " && " : " || ") + b + ")";
            }
            case "not" -> "(!" + expression(p, el.input("BOOL"), scope) + ")";
            case "mc_getitemfromslot" -> {
                // Предмет из слота инвентаря вызывающего игрока (0..8 hotbar).
                String n = expression(p, el.input("SLOT"), scope);
                yield "(_ent instanceof Player __pl ? __pl.getInventory().getItem(Math.min(35, (int)(" + n + "))).copy() : new ItemStack())";
            }
            case "itemstack_make" -> {
                // Конструктор стака: предмет по ID + количество (MCreator: "Item stack from item").
                String id = expression(p, el.input("ITEM"), scope);
                String cnt = expression(p, el.input("COUNT"), scope);
                yield "(new ItemStack(Items.byName(\"" + stripQuotes(id) + "\"), (int)(" + cnt + ")))";
            }
            default -> throw new RuntimeException("Unknown expression element: " + el.type);
        };
    }

    private static String entVar(ProcedureElement el) {
        String v = el.field("ENTITY");
        return v == null ? "_entity" : v;
    }

    /** Строковая координата (выражение или inline-литерал) для createByName. */
    private static String stringLiteral(Procedure p, ProcedureElement.Input in, Scope scope) {
        if (in == null || in.connectedElementId == null) return "\"\"";
        ProcedureElement src = p.byId(in.connectedElementId);
        if (src != null && "text".equals(src.type)) return src.field("TEXT");
        throw new RuntimeException("ожидается текстовый литерал, получен блок: " + (src == null ? "?" : src.type));
    }

    /** Статический тип выражения по его корневому блоку (для type-check валидатора). */
    public static McType valueType(Scope scope, ProcedureElement el) {
        if (el == null) return null;
        return switch (el.type) {
            case "math_number", "itemstack_count", "math_arithmetic", "coord_x", "coord_y", "coord_z",
                 "entity_getpos", "entity_gety", "entity_getz", "player_gethealth" -> McType.NUMBER;
            case "text" -> McType.STRING;
            case "logic_boolean", "logic_compare", "logic_operation", "not", "player_issurvival" -> McType.LOGIC;
            case "variables_get" -> scope.typeOf(el.field("VAR"));
            case "mc_getitemfromslot", "itemstack_make" -> McType.ITEMSTACK;
            default -> null; // неизвестно — не блокируем, javac-уровень добьёт при необходимости
        };
    }
    private static String isVar(ProcedureElement el) {
        String v = el.field("ITEMSTACK");
        return v == null ? "_istack" : v;
    }
    private static String varRef(Scope scope, String name) {
        if (name == null) throw new RuntimeException("variables_get without VAR field");
        McType t = scope.typeOf(name);
        if (t == null) throw new RuntimeException("Undeclared variable used: " + name);
        return name;
    }
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    /** Снимает кавычки с уже экранированной строки-литерала (для встраивания в другой литерал). */
    private static String stripQuotes(String quotedLiteral) {
        if (quotedLiteral.length() >= 2 && quotedLiteral.startsWith("\"") && quotedLiteral.endsWith("\""))
            return quotedLiteral.substring(1, quotedLiteral.length() - 1);
        throw new RuntimeException("itemstack_make: ITEM должен быть строковым литералом (id предмета), получено: " + quotedLiteral);
    }

    /** Обход flow-цепочки: собрать statements для тела execute-execute. */
    public List<String> traverse(Procedure p, ProcedureElement start, Scope scope, FragmentCollector sink) {
        List<String> out = new ArrayList<>();
        ProcedureElement cur = start;
        while (cur != null) {
            switch (cur.type) {
                case "controls_if" -> {
                    String cond = expression(p, cur.input("CONDITION"), scope);
                    var inner = new ArrayList<String>();
                    for (ProcedureElement bodyEl : branchFlowChain(p, cur, 0)) {
                        inner.addAll(traverse(p, bodyEl, scope, sink));
                    }
                    if (!inner.isEmpty()) {
                        out.add("if (" + cond + ") {");
                        out.addAll(inner);
                        out.add("}");
                    } else {
                        out.add("if (" + cond + ") { ; }"); // MCreator emits empty block too
                    }
                    // else-ветки: branches[1..] -> else if / else (как multipleIfStatements в MCreator)
                    for (int bi = 1; bi < cur.branches.size(); bi++) {
                        List<ProcedureElement> brEls = cur.branches.get(bi);
                        boolean isElse = brEls.size() == 1 && brEls.get(0) != null && "controls_if".equals(brEls.get(0).type);
                        if (isElse) {
                            ProcedureElement nested = brEls.get(0);
                            String ncond = expression(p, nested.input("CONDITION"), scope);
                            out.add("else if (" + ncond + ") {");
                            var nnested = new ArrayList<String>();
                            for (ProcedureElement e2 : branchFlowChain(p, nested, 0)) nnested.addAll(traverse(p, e2, scope, sink));
                            out.addAll(nnested.isEmpty() ? List.of(";") : nnested);
                            out.add("}");
                        } else {
                            var nbody = new ArrayList<String>();
                            for (ProcedureElement e2 : brEls) {
                                if (e2 == null) continue;
                                // каждый корень ветки обходим как цепочку flow, не выходя за пределы ветки
                                java.util.Set<String> ids = new java.util.HashSet<>();
                                for (ProcedureElement q : brEls) ids.add(q.id);
                                ProcedureElement c2 = e2;
                                int g2 = 0;
                                while (c2 != null && ids.contains(c2.id) && g2++ < 10000) {
                                    nbody.addAll(traverse(p, c2, scope, sink));
                                    c2 = c2.outputNext == null ? null : p.byId(c2.outputNext);
                                    if (c2 != null && !ids.contains(c2.id)) break;
                                }
                            }
                            out.add("else {");
                            out.addAll(nbody.isEmpty() ? List.of(";") : nbody);
                            out.add("}");
                        }
                    }
                }
                case "controls_forEach" -> {
                    String listExpr = expression(p, cur.input("LIST"), scope);
                    String varName = cur.field("VAR");
                    if (varName == null) throw new RuntimeException("forEach without VAR field");
                    scope.declare(varName, McType.ITEMSTACK); // цикл вводит переменную как ItemStack
                    String tmp = scope.newTemp("list");
                    sink.imports.add("java.util.List");
                    out.add("List<ItemStack> " + tmp + " = " + listExpr + ";");
                    out.add("for (ItemStack " + varName + " : " + tmp + ") {");
                    for (ProcedureElement bodyEl : branchChain(p, cur, 0)) {
                        out.addAll(traverse(p, bodyEl, scope, sink));
                    }
                    out.add("}");
                }
                case "procedures_setvar" -> {
                    String name = cur.field("VAR");
                    String val = expression(p, cur.input("VALUE"), scope);
                    McType t = scope.typeOf(name);
                    if (t == null) throw new RuntimeException("setvar to undeclared variable: " + name);
                    out.add(name + " = " + cast(t, val) + ";");
                }
                case "mc_sendmessage" -> {
                    String msg = expression(p, cur.input("MESSAGE"), scope);
                    sink.imports.add("net.minecraft.network.chat.Component");
                    sink.imports.add("net.minecraftforge.server.ServerLifecycleHooks");
                    // 1.20.1: Component.literal(String, boolean) — вторая проверка стиля
                    out.add("{ ServerLifecycleHooks.getCurrentServer(); /* broadcast stub: */ Component.literal(" + msg + "); }");
                }
                case "mc_spawnentity" -> {
                    String type = cur.field("TYPE");
                    String x = expression(p, cur.input("X"), scope);
                    String y = expression(p, cur.input("Y"), scope);
                    String z = expression(p, cur.input("Z"), scope);
                    sink.imports.add("net.minecraft.world.entity.EntityType");
                    out.add("{ EntityType<?> __et = EntityType.byString(\"" + type + "\").orElse(null); if (__et != null) { Entity __spawned = __et.create(_world); if (__spawned != null) { __spawned.setPos(" + x + ", " + y + ", " + z + "); _world.addFreshEntity(__spawned); } } }");
                }
                case "mc_give_item_to_player" -> {
                    String istack = expression(p, cur.input("ITEMSTACK"), scope);
                    sink.imports.add("net.minecraft.world.item.ItemStack");
                    // Статическая типизация (как type-check валидатор MCreator): выдавать можно только ItemStack
                    ProcedureElement srcEl = cur.input("ITEMSTACK") == null || cur.input("ITEMSTACK").connectedElementId == null
                        ? null : p.byId(cur.input("ITEMSTACK").connectedElementId);
                    McType vt = valueType(scope, srcEl);
                    if (vt != null && vt != McType.ITEMSTACK)
                        throw new RuntimeException("give_item_to_player: ожидается itemstack, получен " + vt + " (блок " + (srcEl == null ? "?" : srcEl.id) + ")");
                    out.add("if (_ent instanceof Player pl) pl.getInventory().add(" + istack + ");");
                }
                case "player_addeffect" -> {
                    String effect = stringLiteral(p, cur.input("EFFECT"), scope);
                    String dur = expression(p, cur.input("DURATION"), scope);
                    // AMPLIFIER — поле с shadow-числом: сначала собственный field, затем connected math_number
                    String ampS = cur.field("AMPLIFIER");
                    if (ampS == null) {
                        ProcedureElement.Input ain = cur.input("AMPLIFIER");
                        if (ain != null && ain.connectedElementId != null) {
                            ProcedureElement ae = p.byId(ain.connectedElementId);
                            if (ae != null && "math_number".equals(ae.type)) ampS = ae.field("NUM");
                        }
                    }
                    int amp = ampS == null ? 0 : Integer.parseInt(ampS.trim());
                    if (amp < 0 || amp > 255) throw new RuntimeException("player_addeffect: AMPLIFIER вне диапазона 0..255: " + amp);
                    sink.imports.add("net.minecraft.world.effect.MobEffectInstance");
                    sink.imports.add("net.minecraft.world.effect.MobEffects");
                    out.add("if (_ent instanceof Player pe) pe.addEffect(new MobEffectInstance(MobEffects." + effect + ", (int)(" + dur + "), " + amp + "));");
                }
                case "player_sethealth" -> {
                    String hp = expression(p, cur.input("HEALTH"), scope);
                    out.add("if (_ent instanceof Player ph) ph.setHealth((float)(" + hp + "));");
                }
                case "mc_play_sound" -> {
                    String snd = stringLiteral(p, cur.input("SOUND"), scope);
                    String cat = cur.field("CATEGORY"); if (cat == null) cat = "MASTER";
                    String x = expression(p, cur.input("X"), scope);
                    String y = expression(p, cur.input("Y"), scope);
                    String z = expression(p, cur.input("Z"), scope);
                    String vol = expression(p, cur.input("VOLUME"), scope);
                    String pitch = expression(p, cur.input("PITCH"), scope);
                    sink.imports.add("net.minecraft.sounds.SoundEvent");
                    sink.imports.add("net.minecraft.sounds.SoundSource");
                    out.add("{ SoundEvent __snd = SoundEvent.createByName(\"" + snd + "\"); _world.playSound(null, " + x + ", " + y + ", " + z + ", __snd, SoundSource." + cat + ", (float)(" + vol + "), (float)(" + pitch + ")); }");
                }
                case "return_logic" -> out.add("return " + expression(p, cur.input("RETURN"), scope) + ";");
                case "return_number" -> out.add("return " + expression(p, cur.input("RETURN"), scope) + ";");
                case "return_string" -> out.add("return " + expression(p, cur.input("RETURN"), scope) + ";");
                case "procedures_callreturn" -> {
                    String proc = cur.field("NAME");
                    List<String> args = new ArrayList<>();
                    for (ProcedureElement.Input in : cur.inputs) args.add(expression(p, in, scope));
                    sink.imports.add("net.reagen.procedures." + capitalize(proc) + "Procedure");
                    out.add(capitalize(proc) + "Procedure.execute(_world, _x, _y, _z, _entity" + (args.isEmpty() ? "" : ", " + String.join(", ", args)) + ");");
                }
                default -> throw new RuntimeException("Unknown statement element: " + cur.type);
            }
            cur = cur.outputNext == null ? null : p.byId(cur.outputNext);
        }
        return out;
    }

    /** Развернуть ветку в цепочку элементов (первые элементы всех веток). */
    private List<ProcedureElement> branchChain(Procedure p, ProcedureElement el, int idx) {
        List<ProcedureElement> chain = new ArrayList<>();
        if (idx < el.branches.size()) {
            for (ProcedureElement e : el.branches.get(idx)) if (e != null) chain.add(e);
        }
        return chain;
    }

    /**
     * Цепочка flow внутри ветки: стартуем с первого элемента и идём по outputNext,
     * НЕ выходя за пределы набора id этой ветки (защита от «утечки» тела в main-flow,
     * как это делает Blockly stack-связность в MCreator).
     */
    private List<ProcedureElement> branchFlowChain(Procedure p, ProcedureElement el, int idx) {
        List<ProcedureElement> raw = branchChain(p, el, idx);
        if (raw.isEmpty()) return raw;
        java.util.Set<String> inBranch = new java.util.HashSet<>();
        for (ProcedureElement e : raw) inBranch.add(e.id);
        List<ProcedureElement> out = new ArrayList<>();
        ProcedureElement cur = raw.get(0);
        int guard = 0;
        while (cur != null && guard++ < 10000) {
            out.add(cur);
            cur = cur.outputNext == null ? null : p.byId(cur.outputNext);
            if (cur != null && !inBranch.contains(cur.id)) break; // ветка закончилась
        }
        return out;
    }

    private static String cast(McType t, String expr) {
        return t.castExpr(expr);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Накопитель импортов/полей при обходе. */
    public static class FragmentCollector {
        public final List<String> imports = new ArrayList<>();
        public final List<String> fields = new ArrayList<>();
    }
}
