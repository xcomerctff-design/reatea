package net.reagen.parser;

import net.reagen.ast.*;

import java.util.List;
import java.util.Map;

/** Парсер JSON-графа процедуры в Procedure AST (аналог Blockly XML -> ProcedureModel). */
public class ProcedureGraphParser {

    public Procedure parse(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Procedure p = new Procedure();
        p.name = Json.str(root.get("name"));
        p.modId = Json.str(root.getOrDefault("modId", "examplemod"));
        p.returnType = McType.from(Json.str(root.getOrDefault("returnType", "logic")));
        p.rootId = Json.str(root.get("rootId"));

        if (root.get("args") != null)
            for (Object o : Json.arr(root.get("args"))) {
                Map<String, Object> a = Json.obj(o);
                p.args.put(Json.str(a.get("name")), McType.from(Json.str(a.get("type"))));
            }
        if (root.get("locals") != null)
            for (Object o : Json.arr(root.get("locals"))) {
                Map<String, Object> a = Json.obj(o);
                p.locals.put(Json.str(a.get("name")), McType.from(Json.str(a.get("type"))));
            }

        for (Object o : Json.arr(root.get("elements"))) {
            Map<String, Object> e = Json.obj(o);
            ProcedureElement el = new ProcedureElement();
            el.id = Json.str(e.get("id"));
            el.type = Json.str(e.get("type"));
            el.x = (int) Json.dbl(e.getOrDefault("x", 0));
            el.y = (int) Json.dbl(e.getOrDefault("y", 0));
            if (e.get("outputNext") != null) el.outputNext = Json.str(e.get("outputNext"));
            if (e.get("fields") != null)
                for (Object fo : Json.arr(e.get("fields"))) {
                    Map<String, Object> f = Json.obj(fo);
                    ProcedureElement.Field fld = new ProcedureElement.Field();
                    fld.name = Json.str(f.get("name"));
                    fld.value = Json.str(f.get("value"));
                    el.fields.add(fld);
                }
            if (e.get("inputs") != null)
                for (Object io : Json.arr(e.get("inputs"))) {
                    Map<String, Object> i = Json.obj(io);
                    ProcedureElement.Input in = new ProcedureElement.Input();
                    in.name = Json.str(i.get("name"));
                    in.shadowType = i.get("shadowType") == null ? null : Json.str(i.get("shadowType"));
                    in.connectedElementId = i.get("connectedElementId") == null ? null : Json.str(i.get("connectedElementId"));
                    el.inputs.add(in);
                }
            if (e.get("branches") != null)
                for (Object bo : Json.arr(e.get("branches"))) {
                    List<ProcedureElement> branch = new java.util.ArrayList<>();
                    if (bo instanceof List<?> seq) {
                        for (Object idObj : seq) {
                            String id = Json.str(idObj);
                            // ссылки на уже распарсенные элементы заполняются вторым проходом ниже
                            branch.add(new RefPlaceholder(id));
                        }
                    }
                    p.elements.put(el.id + "#branch" + el.branches.size(), null); // маркер порядка
                    el.branches.add(branch);
                }
            p.elements.put(el.id, el);
        }
        resolveBranchRefs(p);
        return p;
    }

    /** Плейсхолдер для ссылки на элемент внутри ветки. */
    static class RefPlaceholder extends ProcedureElement {
        RefPlaceholder(String id) { this.id = id; this.type = "@ref"; }
    }

    private void resolveBranchRefs(Procedure p) {
        var clean = new java.util.LinkedHashMap<String, ProcedureElement>();
        for (var e : p.elements.entrySet()) {
            if (!e.getKey().contains("#branch")) clean.put(e.getKey(), e.getValue());
        }
        p.elements.clear();
        p.elements.putAll(clean);
        for (ProcedureElement el : clean.values()) {
            for (List<ProcedureElement> br : el.branches) {
                for (int i = 0; i < br.size(); i++) {
                    ProcedureElement maybe = br.get(i);
                    if (maybe instanceof RefPlaceholder rp) br.set(i, clean.get(rp.id));
                }
            }
        }
    }
}
