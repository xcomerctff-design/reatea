package net.reagen.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Область видимости переменных процедуры (аналог ProcedureInfo/Scope в MCreator). */
public class Scope {
    public final Map<String, McType> variables = new LinkedHashMap<>();
    /** Счётчик для генерации уникальных имён локалов (temp0, temp1...). */
    private int tmpCounter = 0;

    public void declare(String name, McType type) { variables.put(name, type); }

    public McType typeOf(String name) { return variables.get(name); }

    public String newTemp(String prefix) { return prefix + (tmpCounter++); }

    /** Проверка: все ли использованные имена объявлены. */
    public List<String> undefinedNames(List<String> used) {
        List<String> out = new ArrayList<>();
        for (String u : used) if (!variables.containsKey(u) && !u.startsWith("temp")) out.add(u);
        return out;
    }
}
