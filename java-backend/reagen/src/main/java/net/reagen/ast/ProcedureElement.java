package net.reagen.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Элемент графа процедуры (аналог Blockly-блока). Каждый элемент знает свой тип,
 * параметры и связи (outputNext — следующий блок, branches — ветки if/loop).
 */
public class ProcedureElement {
    public String id;
    public String type;                       // "procedures_callreturn", "controls_if", "math_number", ...
    public int x, y;                          // позиция в редакторе
    public List<Field> fields = new ArrayList<>();
    public List<Input> inputs = new ArrayList<>();
    public String outputNext;                 // id следующего элемента (flow connection)
    public List<List<ProcedureElement>> branches = new ArrayList<>(); // для if/loop тел

    public static class Field { public String name; public String value; }
    public static class Input {
        public String name;                   // "CONDITION", "VALUE0", "BOTH" ...
        public String shadowType;             // тип подсказки (math_number, text...)
        public String connectedElementId;     // id подключёного value-элемента
    }

    public String field(String name) {
        for (Field f : fields) if (f.name.equals(name)) return f.value;
        return null;
    }

    public Input input(String name) {
        for (Input i : inputs) if (i.name.equals(name)) return i;
        return null;
    }
}
