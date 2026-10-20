package net.reagen.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Целая процедура: метаданные + граф элементов (аналог ProcedureModel в MCreator). */
public class Procedure {
    public String name;            // "custom_name"
    public String modId;           // мод, которому принадлежит
    public McType returnType = McType.LOGIC;
    /** Аргументы процедуры (входные связи из вызова). */
    public final Map<String, McType> args = new LinkedHashMap<>();
    /** Локальные переменные. */
    public final Map<String, McType> locals = new LinkedHashMap<>();
    /** Элементы графа по id. */
    public final Map<String, ProcedureElement> elements = new LinkedHashMap<>();
    public String rootId;          // стартовый блок (procedures_returnskip / start)

    public ProcedureElement byId(String id) { return elements.get(id); }

    /** Все имена переменных, объявленные в скоупе процедуры. */
    public Scope buildScope() {
        Scope s = new Scope();
        args.forEach(s::declare);
        locals.forEach(s::declare);
        return s;
    }

    /** Линейная цепочка от корня (для генерации statements). */
    public List<ProcedureElement> flowChain() {
        List<ProcedureElement> out = new ArrayList<>();
        ProcedureElement cur = byId(rootId);
        int guard = 0;
        while (cur != null && guard++ < 10000) {
            out.add(cur);
            cur = cur.outputNext == null ? null : byId(cur.outputNext);
        }
        return out;
    }
}
