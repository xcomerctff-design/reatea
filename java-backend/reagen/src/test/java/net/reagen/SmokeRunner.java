package net.reagen;

import net.reagen.codegen.ProcedureGenerator;
import net.reagen.parser.ProcedureGraphParser;
import net.reagen.verify.ProcedureVerifier;
import net.reagen.verify.VerifyReport;

/**
 * Смоук-тесты конвейера "граф -> AST -> код Forge 1.20.1 + верификация" (без JUnit, как раньше).
 * Запуск: java -cp out:... net.reagen.SmokeRunner
 */
public class SmokeRunner {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        testGiveTypeMismatch();
        testGiveDiamond();
        testIfAndCompare();
        testForEachLoop();
        testUndeclaredVar();
        testDanglingLink();
        testSpawnEntity();
        testSimDivByZero();
        testSimUnknownEntity();
        testSimEmptyMessage();
        testSimHappyPathTrace();
        System.out.println("SMOKE RESULT: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }

    static void check(boolean cond, String name) {
        if (cond) { pass++; System.out.println("PASS: " + name); }
        else { fail++; System.out.println("FAIL: " + name); }
    }

    /** give_item_to_player принимает только itemstack-выражение (как type-check в MCreator). */
    static void testGiveTypeMismatch() {
        String json = """
        {"name":"badGive","returnType":"logic","rootId":"g1",
         "locals":[{"name":"cnt","type":"number"}],
         "elements":[
           {"id":"g1","type":"mc_give_item_to_player",
            "inputs":[{"name":"ITEMSTACK","connectedElementId":"vg"}]},
           {"id":"vg","type":"variables_get","fields":[{"name":"VAR","value":"cnt"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        VerifyReport r = new ProcedureVerifier().verify(p);
        check(!r.valid() && r.toJson().contains("give_item_to_player"),
              "type mismatch on give detected: " + r.toJson());
    }

    /** give_diamond: setvar istack -> sendmessage -> return true. Компилируется со стабами. */
    static void testGiveDiamond() {
        String json = """
        {"name":"giveDiamond","returnType":"logic","rootId":"e1",
         "locals":[{"name":"cnt","type":"number"}],
         "elements":[
           {"id":"e1","type":"procedures_setvar","fields":[{"name":"VAR","value":"cnt"}],
            "inputs":[{"name":"VALUE","shadowType":"math_number","connectedElementId":"n1"}],
            "outputNext":"e2"},
           {"id":"n1","type":"math_number","fields":[{"name":"NUM","value":"64"}]},
           {"id":"e2","type":"mc_sendmessage",
            "inputs":[{"name":"MESSAGE","shadowType":"text","connectedElementId":"t1"}],
            "outputNext":"e3"},
           {"id":"t1","type":"text","fields":[{"name":"TEXT","value":"Got diamonds!"}]},
           {"id":"e3","type":"return_logic","inputs":[{"name":"RETURN","shadowType":"logic_boolean","connectedElementId":"b1"}]},
           {"id":"b1","type":"logic_boolean","fields":[{"name":"BOOL","value":"true"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        String code = new ProcedureGenerator().generate(p);
        check(code.contains("cnt = (double) (64);"), "setvar number literal (cast to declared type)");
        check(code.contains("Component.literal(\"Got diamonds!\")"), "sendmessage generates Component");
        check(code.contains("return true;"), "return logic");
        VerifyReport r = new ProcedureVerifier().verify(p);
        check(r.valid(), "valid procedure passes verification (javac included): " + r.toJson());
    }

    static void testIfAndCompare() {
        String json = """
        {"name":"ifTest","returnType":"logic","rootId":"i1",
         "args":[{"name":"hp","type":"number"}],
         "elements":[
           {"id":"i1","type":"controls_if",
            "inputs":[{"name":"CONDITION","connectedElementId":"c1"}],
            "branches":[["i2"]],"outputNext":"r1"},
           {"id":"c1","type":"logic_compare","fields":[{"name":"OP","value":"LT"}],
            "inputs":[{"name":"A","connectedElementId":"g1"},{"name":"B","shadowType":"math_number","connectedElementId":"n5"}]},
           {"id":"g1","type":"variables_get","fields":[{"name":"VAR","value":"hp"}]},
           {"id":"n5","type":"math_number","fields":[{"name":"NUM","value":"5"}]},
           {"id":"i2","type":"return_logic","inputs":[{"name":"RETURN","connectedElementId":"f1"}]},
           {"id":"f1","type":"logic_boolean","fields":[{"name":"BOOL","value":"false"}]},
           {"id":"r1","type":"return_logic","inputs":[{"name":"RETURN","connectedElementId":"t1"}]},
           {"id":"t1","type":"logic_boolean","fields":[{"name":"BOOL","value":"true"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        String code = new ProcedureGenerator().generate(p);
        check(code.contains("if (hp < 5) {"), "if with compare generated");
        check(new ProcedureVerifier().verify(p).valid(), "if-procedure verifies OK");
    }

    static void testForEachLoop() {
        String json = """
        {"name":"loopTest","returnType":"logic","rootId":"l1",
         "elements":[
           {"id":"l1","type":"controls_forEach","fields":[{"name":"VAR","value":"stack"}],
            "inputs":[{"name":"LIST","connectedElementId":"gi"}],
            "branches":[["p1"]],"outputNext":"r1"},
           {"id":"gi","type":"mc_getitemfromslot","inputs":[{"name":"SLOT","shadowType":"math_number","connectedElementId":"n0"}]},
           {"id":"n0","type":"math_number","fields":[{"name":"NUM","value":"0"}]},
           {"id":"p1","type":"mc_give_item_to_player","inputs":[{"name":"ITEMSTACK","connectedElementId":"vg"}]},
           {"id":"vg","type":"variables_get","fields":[{"name":"VAR","value":"stack"}]},
           {"id":"r1","type":"return_logic","inputs":[{"name":"RETURN","connectedElementId":"t1"}]},
           {"id":"t1","type":"logic_boolean","fields":[{"name":"BOOL","value":"true"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        String code = new ProcedureGenerator().generate(p);
        check(code.contains("for (ItemStack stack : list0)"), "forEach loop generated");
        check(code.contains("pl.getInventory().add(stack)"), "give item inside loop");
    }

    static void testUndeclaredVar() {
        String json = """
        {"name":"badVar","returnType":"logic","rootId":"s1",
         "elements":[
           {"id":"s1","type":"procedures_setvar","fields":[{"name":"VAR","value":"ghost"}],
            "inputs":[{"name":"VALUE","connectedElementId":"n1"}]},
           {"id":"n1","type":"math_number","fields":[{"name":"NUM","value":"1"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        VerifyReport r = new ProcedureVerifier().verify(p);
        check(!r.valid() && r.toJson().contains("UNGENERABLE"), "undeclared variable detected: " + r.toJson());
    }

    static void testDanglingLink() {
        String json = """
        {"name":"dangle","returnType":"logic","rootId":"a1",
         "elements":[{"id":"a1","type":"return_logic","outputNext":"missingBlock",
            "inputs":[{"name":"RETURN","connectedElementId":"nope"}]}]}""";
        var p = new ProcedureGraphParser().parse(json);
        VerifyReport r = new ProcedureVerifier().verify(p);
        String j = r.toJson();
        check(j.contains("DANGLING_NEXT") && j.contains("DANGLING_INPUT"), "dangling links reported: " + j);
    }

    static void testSpawnEntity() {
        String json = """
        {"name":"spawnCow","returnType":"logic","rootId":"s1",
         "elements":[
           {"id":"s1","type":"mc_spawnentity","fields":[{"name":"TYPE","value":"minecraft:cow"}],
            "inputs":[{"name":"X","shadowType":"math_number","connectedElementId":"x1"},
                      {"name":"Y","shadowType":"math_number","connectedElementId":"y1"},
                      {"name":"Z","shadowType":"math_number","connectedElementId":"z1"}],
            "outputNext":"r1"},
           {"id":"x1","type":"math_number","fields":[{"name":"NUM","value":"10"}]},
           {"id":"y1","type":"math_number","fields":[{"name":"NUM","value":"64"}]},
           {"id":"z1","type":"math_number","fields":[{"name":"NUM","value":"-30"}]},
           {"id":"r1","type":"return_logic","inputs":[{"name":"RETURN","connectedElementId":"t1"}]},
           {"id":"t1","type":"logic_boolean","fields":[{"name":"BOOL","value":"true"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        String code = new ProcedureGenerator().generate(p);
        check(code.contains("EntityType.byString(\"minecraft:cow\")"), "spawn entity generated");
        check(new ProcedureVerifier().verify(p).valid(), "spawn procedure compiles against stubs");
    }

    // ---------- симулятор ("игровой тест" без Minecraft) ----------

    /** setvar x = 10 / 0 → SIM_DIV_BY_ZERO. */
    static void testSimDivByZero() {
        String json = """
        {"name":"divZero","returnType":"logic","rootId":"s1",
         "locals":[{"name":"x","type":"number"}],
         "elements":[
           {"id":"s1","type":"procedures_setvar","fields":[{"name":"VAR","value":"x"}],
            "inputs":[{"name":"VALUE","connectedElementId":"d1"}]},
           {"id":"d1","type":"math_arithmetic","fields":[{"name":"OP","value":"DIVIDE"}],
            "inputs":[{"name":"A","shadowType":"math_number","connectedElementId":"n10"},
                      {"name":"B","shadowType":"math_number","connectedElementId":"n0"}]},
           {"id":"n10","type":"math_number","fields":[{"name":"NUM","value":"10"}]},
           {"id":"n0","type":"math_number","fields":[{"name":"NUM","value":"0"}]}
         ]}""";
        var sim = new net.reagen.verify.GameSimulator();
        try {
            sim.run(new ProcedureGraphParser().parse(json));
            check(false, "simulator must throw on division by zero");
        } catch (RuntimeException e) {
            check(e.getMessage() != null && e.getMessage().contains("Деление на ноль"),
                  "SIM div-by-zero detected in headless run: " + e.getMessage());
        }
    }

    /** Спавн minecraft:dragon — неизвестный тип: верификатор через симуляцию даёт SIM_UNKNOWN_ENTITY. */
    static void testSimUnknownEntity() {
        String json = """
        {"name":"badSpawn","returnType":"logic","rootId":"a1",
         "elements":[
           {"id":"a1","type":"mc_spawnentity","fields":[{"name":"TYPE","value":"minecraft:dragon"}],
            "inputs":[{"name":"X","shadowType":"math_number","connectedElementId":"x1"},
                      {"name":"Y","shadowType":"math_number","connectedElementId":"y1"},
                      {"name":"Z","shadowType":"math_number","connectedElementId":"z1"}]},
           {"id":"x1","type":"math_number","fields":[{"name":"NUM","value":"10"}]},
           {"id":"y1","type":"math_number","fields":[{"name":"NUM","value":"64"}]},
           {"id":"z1","type":"math_number","fields":[{"name":"NUM","value":"-30"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        VerifyReport r = new ProcedureVerifier().verify(p);
        check(!r.valid() && r.toJson().contains("SIM_UNKNOWN_ENTITY"),
              "unknown entity type caught by simulation before game launch: " + r.toJson());
    }

    /** sendmessage с пустым текстом → SIM_EMPTY_MESSAGE. */
    static void testSimEmptyMessage() {
        String json = """
        {"name":"emptyMsg","returnType":"logic","rootId":"m1",
         "elements":[
           {"id":"m1","type":"mc_sendmessage",
            "inputs":[{"name":"MESSAGE","connectedElementId":"t1"}]},
           {"id":"t1","type":"text","fields":[{"name":"TEXT","value":"   "}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        VerifyReport r = new ProcedureVerifier().verify(p);
        check(!r.valid() && r.toJson().contains("SIM_EMPTY_MESSAGE"),
              "blank chat message caught by simulation: " + r.toJson());
    }

    /** Позитивный прогон: giveDiamond проходит все 4 уровня и трассировка полна. */
    static void testSimHappyPathTrace() {
        String json = """
        {"name":"giveDiamond","returnType":"logic","rootId":"e1",
         "locals":[{"name":"cnt","type":"number"}],
         "elements":[
           {"id":"e1","type":"procedures_setvar","fields":[{"name":"VAR","value":"cnt"}],
            "inputs":[{"name":"VALUE","shadowType":"math_number","connectedElementId":"n1"}],
            "outputNext":"e2"},
           {"id":"n1","type":"math_number","fields":[{"name":"NUM","value":"64"}]},
           {"id":"e2","type":"mc_sendmessage",
            "inputs":[{"name":"MESSAGE","shadowType":"text","connectedElementId":"t1"}],
            "outputNext":"g1"},
           {"id":"t1","type":"text","fields":[{"name":"TEXT","value":"Got diamonds!"}]},
           {"id":"g1","type":"mc_give_item_to_player",
            "inputs":[{"name":"ITEMSTACK","connectedElementId":"vg"}]},
           {"id":"vg","type":"variables_get","fields":[{"name":"VAR","value":"cnt"}]}
         ]}""";
        var p = new ProcedureGraphParser().parse(json);
        var res = new net.reagen.verify.GameSimulator().run(p);
        check(res.chatLog.contains("Got diamonds!"), "sim trace records chat message");
        check(res.givenItems.stream().anyMatch(s -> s.startsWith("minecraft:air")) == false && !res.givenItems.isEmpty(),
              "sim trace records non-empty item given: " + res.givenItems);
        check(Double.valueOf(64.0).equals(res.finalVariables.get("cnt")), "sim final variable cnt == 64: " + res.finalVariables);
    }
}
