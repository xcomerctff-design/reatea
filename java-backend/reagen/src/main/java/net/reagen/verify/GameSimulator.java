package net.reagen.verify;

import net.reagen.ast.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless-симулятор процедур — «игровой тест» без запуска Minecraft.
 *
 * Интерпретирует граф процедуры напрямую (не Java-код) на мок-контексте мира:
 *   - Level/Entity/Player — лёгкие моки с той же семантикой, что стабы и реальный API 1.20.1;
 *   - проверяются рантайм-дефекты логики, которые статика не видит: деление на ноль,
 *     спавн несуществующей сущности, выдача пустого стака, сообщения без текста,
 *     обращения к переменным вне области видимости, бесконечные цепочки.
 *
 * Это уровень 4 конвейера проверки из редактора (см. docs/ARCHITECTURE.md):
 * граф → валидатор → генерация → javac-компиляция → СИМУЛЯЦИЯ ПОВЕДЕНИЯ.
 */
public class GameSimulator {

    /** Лимит шагов интерпретации — защита от циклов в flow-графе (в MCreator их нет by design). */
    private static final int MAX_STEPS = 100_000;

    public static class SimResult {
        public Object returnValue;
        public final List<String> chatLog = new ArrayList<>();
        public final List<String> spawnedEntities = new ArrayList<>();
        public final List<String> givenItems = new ArrayList<>();
        public final Map<String, Object> finalVariables = new LinkedHashMap<>();

        /** JSON трассировки игрового теста для панели предпросмотра редактора. */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"returnValue\":").append(returnValue == null ? "null" : "\"" + returnValue + "\"");
            sb.append(",\"chatLog\":").append(arr(chatLog));
            sb.append(",\"spawnedEntities\":").append(arr(spawnedEntities));
            sb.append(",\"givenItems\":").append(arr(givenItems));
            sb.append(",\"finalVariables\":{");
            int i = 0;
            for (var e : finalVariables.entrySet()) {
                if (i++ > 0) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            sb.append("}}");
            return sb.toString();
        }
        private static String arr(List<String> xs) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(escJson(xs.get(i))).append('"'); }
            return sb.append(']').toString();
        }
        private static String escJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // ---------- мок-мир ----------

    /** Игровая вселенная симуляции: известные типы сущностей как в vanilla registry. */
    static final Set<String> KNOWN_ENTITY_TYPES = Set.of(
        "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
        "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:player",
        "minecraft:item", "minecraft:armor_stand", "minecraft:bat", "minecraft:wolf");

    /** Ванильные эффекты (MobEffects.*) — то, что умеет генерировать player_addeffect. */
    static final Set<String> KNOWN_EFFECTS = Set.of(
        "REGENERATION", "SPEED", "POISON", "WEAKNESS", "STRENGTH");
    record MockPos(double x, double y, double z) {}

    static class MockLevel {
        boolean addFreshEntity(String typeId) { return KNOWN_ENTITY_TYPES.contains(typeId); }
    }

    static class MockItemStack {
        final String itemId; final int count;
        MockItemStack(String id, int c) { itemId = id; count = c; }
        static final MockItemStack EMPTY = new MockItemStack("minecraft:air", 0);
        boolean isEmpty() { return count <= 0; }
    }

    static class MockPlayer {
        final List<MockItemStack> inventory = new ArrayList<>();
        double health = 20.0;
        double maxHealth = 20.0;
        boolean survival = true;
        final Map<String, Integer> effects = new LinkedHashMap<>(); // эффект -> длительность тиков
        void add(MockItemStack s) { if (!s.isEmpty()) inventory.add(s); }
        /** Семантика PlayerInventory.getItem(slot): пустой стак, если слот не заполнен. */
        MockItemStack getItem(int slot) {
            return (slot >= 0 && slot < inventory.size()) ? inventory.get(slot) : MockItemStack.EMPTY;
        }
    }

    /** Контекст исполнения: типизация из Scope + значения переменных + мок-мир + трассировка. */
    class Ctx {
        final Scope scope;
        final Map<String, Object> vars = new LinkedHashMap<>();
        final SimResult trace = new SimResult();
        final MockLevel world = new MockLevel();
        final MockPlayer player = new MockPlayer();
        int steps = 0;
        Ctx(Scope s) { this.scope = s; }
    }

    // ---------- публичный API ----------

    public SimResult run(Procedure p) {
        Scope scope = p.buildScope();
        Ctx ctx = new Ctx(scope);
        // инициализировать locals/args дефолтами (как в генераторе)
        for (var e : p.locals.entrySet()) ctx.vars.put(e.getKey(), defaultOf(e.getValue()));
        for (var e : p.args.entrySet())   ctx.vars.put(e.getKey(), defaultOf(e.getValue()));
        ProcedureElement root = p.byId(p.rootId);
        if (root != null) execChain(p, root, ctx, new HashSet<>());
        ctx.trace.finalVariables.putAll(ctx.vars);
        return ctx.trace;
    }

    /** Прогон симуляции с записью найденных дефектов в отчёт верификатора. */
    public void simulate(Procedure p, VerifyReport r) {
        SimResult res;
        try {
            res = run(p);
        } catch (SimException e) {
            r.error(e.elementId, e.rule, e.getMessage());
            return;
        } catch (RuntimeException e) {
            r.error("sim", "SIM_CRASH", "Симуляция упала с внутренним исключением: " + e);
            return;
        }
        // пост-условия «геймплейного теста»
        if (p.returnType != McType.LOGIC && res.returnValue == null)
            r.warn(p.rootId, "SIM_NO_RETURN_VALUE", "За трассировкой процедура завершается без значения при returnType=" + p.returnType);
        for (String bad : res.spawnedEntities)
            r.error("sim", "SIM_UNKNOWN_ENTITY", "Спавн неизвестного типа сущности: " + bad + " (в игре create() вернёт null)");
        for (String g : res.givenItems)
            if ("EMPTY".equals(g))
                r.error("sim", "SIM_GIVE_EMPTY_STACK", "Выдача пустого ItemStack игроку — в игре silently no-op");
    }

    // ---------- интерпретатор flow ----------

    static class SimException extends RuntimeException {
        final String elementId, rule;
        SimException(String elementId, String rule, String msg) { super(msg); this.elementId = elementId; this.rule = rule; }
    }

    private void execChain(Procedure p, ProcedureElement start, Ctx ctx, Set<String> visiting) {
        ProcedureElement cur = start;
        while (cur != null) {
            if (++ctx.steps > MAX_STEPS)
                throw new SimException(cur.id, "SIM_STEP_LIMIT", "Превышен лимит шагов — вероятна циклическая связка outputNext");
            if (!visiting.add(cur.id))
                throw new SimException(cur.id, "SIM_FLOW_CYCLE", "Цикл в flow-цепочке через блок " + cur.id);
            execOne(p, cur, ctx, visiting);
            cur = cur.outputNext == null ? null : p.byId(cur.outputNext);
        }
    }

    private void execOne(Procedure p, ProcedureElement el, Ctx ctx, Set<String> visiting) {
        switch (el.type) {
            case "controls_if" -> {
                boolean cond = truthy(expr(p, el.input("CONDITION"), ctx), el);
                int taken = -1;
                if (cond) taken = 0;
                for (int bi = 1; bi < el.branches.size() && taken < 0; bi++) {
                    List<ProcedureElement> br = el.branches.get(bi);
                    if (br.size() == 1 && br.get(0) != null && "controls_if".equals(br.get(0).type)) {
                        ProcedureElement nested = br.get(0);
                        if (truthy(expr(p, nested.input("CONDITION"), ctx), nested)) taken = bi;
                    } else if (br.isEmpty()) {
                        taken = bi; // чистый else
                    } else if (br.get(0) != null && !"controls_if".equals(br.get(0).type) && bi == el.branches.size() - 1 && !cond) {
                        taken = bi; // последний не-if branch трактуем как else
                    }
                }
                if (taken >= 0) execBranchFlow(p, el, taken, ctx, visiting);
            }
            case "controls_forEach" -> {
                String varName = el.field("VAR");
                if (varName == null) throw new SimException(el.id, "SIM_MISSING_FIELD", "forEach без имени переменной");
                Object listVal = expr(p, el.input("LIST"), ctx);
                List<?> list = listVal instanceof List<?> l ? l : List.of(wrapListItem(listVal));
                ctx.scope.declare(varName, McType.ITEMSTACK);
                for (Object item : list) {
                    ctx.vars.put(varName, item); // значение итератора
                    execBranchFlow(p, el, 0, ctx, visiting);
                }
            }
            case "procedures_setvar" -> {
                String name = el.field("VAR");
                McType t = ctx.scope.typeOf(name);
                if (t == null) throw new SimException(el.id, "SIM_UNDECLARED_VAR", "setvar к необъявленной переменной: " + name);
                Object v = expr(p, el.input("VALUE"), ctx);
                ctx.vars.put(name, coerce(v, t, el));
            }
            case "mc_sendmessage" -> {
                Object m = expr(p, el.input("MESSAGE"), ctx);
                String s = String.valueOf(m);
                if (s.isBlank()) throw new SimException(el.id, "SIM_EMPTY_MESSAGE", "sendmessage с пустым текстом — игрок ничего не увидит");
                ctx.trace.chatLog.add(s);
            }
            case "mc_spawnentity" -> {
                String type = el.field("TYPE");
                if (type == null || type.isBlank()) throw new SimException(el.id, "SIM_SPAWN_NO_TYPE", "spawnentity без TYPE");
                double x = num(expr(p, el.input("X"), ctx), el);
                double y = num(expr(p, el.input("Y"), ctx), el);
                double z = num(expr(p, el.input("Z"), ctx), el);
                if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isNaN(z))
                    throw new SimException(el.id, "SIM_NAN_POSITION", "Спавн с NaN/Infinity координатами — краш чанк-системы");
                if (y < -64 || y > 320)
                    r_out_of_world(ctx, el, y);
                if (!ctx.world.addFreshEntity(type)) ctx.trace.spawnedEntities.add(type);
            }
            case "mc_give_item_to_player" -> {
                Object istack = expr(p, el.input("ITEMSTACK"), ctx);
                MockItemStack st = toStack(istack);
                if (st.isEmpty()) ctx.trace.givenItems.add("EMPTY");
                else { ctx.player.add(st); ctx.trace.givenItems.add(st.itemId + "x" + st.count); }
            }
            case "player_addeffect" -> {
                Object ev = expr(p, el.input("EFFECT"), ctx);
                String effect = String.valueOf(ev);
                if (!KNOWN_EFFECTS.contains(effect))
                    throw new SimException(el.id, "SIM_UNKNOWN_EFFECT",
                        "Unknown effect '" + effect + "' — MobEffects." + effect + " not in 1.20.1 registry");
                double dur = num(expr(p, el.input("DURATION"), ctx), el);
                if (dur < 0) throw new SimException(el.id, "SIM_NEGATIVE_DURATION",
                    "Negative effect duration (" + dur + " ticks)");
                String ampS = el.field("AMPLIFIER");
                if (ampS == null) {
                    ProcedureElement.Input ain = el.input("AMPLIFIER");
                    if (ain != null && ain.connectedElementId != null) {
                        ProcedureElement ae = p.byId(ain.connectedElementId);
                        if (ae != null && "math_number".equals(ae.type)) ampS = ae.field("NUM");
                    }
                }
                int amp = ampS == null ? 0 : (int) parseNum(ampS, el);
                if (amp < 0 || amp > 255)
                    throw new SimException(el.id, "SIM_BAD_AMPLIFIER", "Effect amplifier out of 0..255: " + amp);
                ctx.player.effects.put(effect, (int) dur);
            }
            case "player_sethealth" -> {
                double hp = num(expr(p, el.input("HEALTH"), ctx), el);
                if (Double.isNaN(hp))
                    throw new SimException(el.id, "SIM_NAN_HEALTH", "NaN health is undefined behavior in game");
                if (hp < 0)
                    throw new SimException(el.id, "SIM_NEGATIVE_HEALTH", "sethealth(<0): game clamps to 0 — dead player, check logic");
                if (hp > ctx.player.maxHealth)
                    ctx.trace.chatLog.add("WARN:health_above_max:" + hp + ">" + ctx.player.maxHealth);
                ctx.player.health = Math.min(hp, ctx.player.maxHealth);
            }
            case "mc_play_sound" -> {
                String snd = String.valueOf(expr(p, el.input("SOUND"), ctx));
                if (snd.isBlank()) throw new SimException(el.id, "SIM_EMPTY_SOUND", "playSound with empty sound id");
                double vol = num(expr(p, el.input("VOLUME"), ctx), el);
                double pitch = num(expr(p, el.input("PITCH"), ctx), el);
                if (vol < 0 || vol > 1)
                    ctx.trace.chatLog.add("WARN:sound_volume_out_of_range:" + vol);
                if (pitch < 0.5 || pitch > 2.0)
                    ctx.trace.chatLog.add("WARN:sound_pitch_out_of_range:" + pitch);
            }
            case "return_logic", "return_number", "return_string" -> {
                ctx.trace.returnValue = expr(p, el.input("RETURN"), ctx);
            }
            default -> throw new SimException(el.id, "SIM_UNSUPPORTED_BLOCK", "Симулятор не поддерживает блок: " + el.type);
        }
    }

    private void r_out_of_world(Ctx ctx, ProcedureElement el, double y) {
        // предупреждение, не ошибка: в 1.20.1 за пределами build-limit спавн просто не виден
        ctx.trace.chatLog.add("WARN:spawn_y_out_of_bounds:" + y);
    }

    private void execBranchFlow(Procedure p, ProcedureElement el, int idx, Ctx ctx, Set<String> visiting) {
        List<ProcedureElement> raw = el.branches.get(idx);
        if (raw.isEmpty()) return;
        Set<String> inBranch = new HashSet<>();
        for (ProcedureElement e : raw) if (e != null) inBranch.add(e.id);
        ProcedureElement cur = raw.get(0);
        while (cur != null && inBranch.contains(cur.id)) {
            if (++ctx.steps > MAX_STEPS)
                throw new SimException(cur.id, "SIM_STEP_LIMIT", "Превышен лимит шагов внутри ветки");
            execOne(p, cur, ctx, visiting);
            cur = cur.outputNext == null ? null : p.byId(cur.outputNext);
        }
    }

    // ---------- выражения ----------

    private Object expr(Procedure p, ProcedureElement.Input input, Ctx ctx) {
        if (input == null || input.connectedElementId == null) {
            ProcedureElement fake = new ProcedureElement();
            fake.type = switch (input == null || input.shadowType == null ? "" : input.shadowType) {
                case "math_number" -> "math_number";
                case "text" -> "text";
                case "logic_boolean" -> "logic_boolean";
                default -> "logic_boolean";
            };
            fake.fields.add(field("NUM", "0"));
            fake.fields.add(field("TEXT", ""));
            fake.fields.add(field("BOOL", "false"));
            return evalExpr(p, fake, ctx);
        }
        ProcedureElement el = p.byId(input.connectedElementId);
        if (el == null) throw new SimException(input.name, "SIM_DANGLING_EXPR", "Висячий вход выражения: " + input.connectedElementId);
        return evalExpr(p, el, ctx);
    }

    private static ProcedureElement.Field field(String n, String v) {
        ProcedureElement.Field f = new ProcedureElement.Field(); f.name = n; f.value = v; return f;
    }

    private Object evalExpr(Procedure p, ProcedureElement el, Ctx ctx) {
        return switch (el.type) {
            case "math_number" -> parseNum(el.field("NUM"), el);
            case "text" -> el.field("TEXT") == null ? "" : el.field("TEXT");
            case "logic_boolean" -> Boolean.parseBoolean(el.field("BOOL"));
            case "variables_get" -> {
                String v = el.field("VAR");
                if (!ctx.vars.containsKey(v) && ctx.scope.typeOf(v) == null)
                    throw new SimException(el.id, "SIM_UNDECLARED_VAR", "Чтение необъявленной переменной: " + v);
                yield ctx.vars.getOrDefault(v, defaultOf(ctx.scope.typeOf(v)));
            }
            case "logic_compare" -> compare(el.field("OP"),
                expr(p, el.input("A"), ctx), expr(p, el.input("B"), ctx), el);
            case "math_arithmetic" -> arith(el.field("OP"),
                num(expr(p, el.input("A"), ctx), el), num(expr(p, el.input("B"), ctx), el), el);
            case "logic_operation" -> {
                boolean isAnd = "AND".equals(el.field("OP"));
                boolean a = truthy(expr(p, el.input("A"), ctx), el);
                if (isAnd && !a) yield false;
                if (!isAnd && a) yield true;
                yield truthy(expr(p, el.input("B"), ctx), el);
            }
            case "not" -> !truthy(expr(p, el.input("BOOL"), ctx), el);
            case "itemstack_count" -> {
                Object o = ctx.vars.get(el.field("ITEMSTACK"));
                yield (double) toStack(o).count;
            }
            case "entity_getpos", "coord_x" -> 0.0;
            case "entity_gety" -> 64.0;
            case "entity_getz" -> 0.0;
            case "player_gethealth" -> ctx.player.health;
            case "player_issurvival" -> ctx.player.survival;
            case "mc_getitemfromslot" -> {
                // Копия семантики generated-кода: __pl.getInventory().getItem(min(35, slot)).copy()
                int slot = (int) num(expr(p, el.input("SLOT"), ctx), el);
                yield ctx.player.getItem(Math.min(35, Math.max(0, slot)));
            }
            case "itemstack_make" -> {
                Object idv = expr(p, el.input("ITEM"), ctx);
                int cnt = (int) num(expr(p, el.input("COUNT"), ctx), el);
                yield new MockItemStack(String.valueOf(idv), cnt);
            }
            default -> throw new SimException(el.id, "SIM_UNSUPPORTED_EXPR", "Симулятор не поддерживает выражение: " + el.type);
        };
    }

    private Object defaultOf(McType t) {
        if (t == null) return null;
        return switch (t) {
            case NUMBER -> 0.0;
            case STRING -> "";
            case LOGIC -> false;
            case ITEMSTACK -> MockItemStack.EMPTY;
            default -> null;
        };
    }

    private Object wrapListItem(Object v) { return v instanceof MockItemStack ? v : MockItemStack.EMPTY; }

    private MockItemStack toStack(Object o) {
        if (o instanceof MockItemStack s) return s;
        if (o instanceof String sid) return new MockItemStack(sid, 1);
        return MockItemStack.EMPTY;
    }

    private Object coerce(Object v, McType t, ProcedureElement el) {
        return switch (t) {
            case NUMBER -> num(v, el);
            case STRING -> String.valueOf(v);
            case LOGIC -> truthy(v, el);
            default -> v;
        };
    }

    private double num(Object v, ProcedureElement el) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { throw new SimException(el.id, "SIM_TYPE_MISMATCH", "Ожидалось число, получено: " + v); }
    }

    private boolean truthy(Object v, ProcedureElement el) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v == null) return false;
        return Boolean.parseBoolean(v.toString());
    }

    private double parseNum(String s, ProcedureElement el) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new SimException(el.id, "SIM_BAD_LITERAL", "Некорректный числовой литерал: " + s); }
    }

    private Object compare(String op, Object a, Object b, ProcedureElement el) {
        int c;
        if (a instanceof String sa && b instanceof String sb) c = sa.compareTo(sb);
        else c = Double.compare(num(a, el), num(b, el));
        return switch (op) {
            case "EQ" -> c == 0; case "NEQ" -> c != 0;
            case "LT" -> c < 0;  case "LTE" -> c <= 0;
            case "GT" -> c > 0;  case "GTE" -> c >= 0;
            default -> throw new SimException(el.id, "SIM_BAD_OP", "Неизвестная операция сравнения: " + op);
        };
    }

    private Object arith(String op, double a, double b, ProcedureElement el) {
        return switch (op) {
            case "ADD" -> a + b;
            case "MINUS" -> a - b;
            case "MULTIPLY" -> a * b;
            case "DIVIDE" -> {
                if (b == 0) throw new SimException(el.id, "SIM_DIV_BY_ZERO", "Деление на ноль в процедуре (в игре даст Infinity/NaN)");
                yield a / b;
            }
            default -> throw new SimException(el.id, "SIM_BAD_OP", "Неизвестная арифметическая операция: " + op);
        };
    }
}
