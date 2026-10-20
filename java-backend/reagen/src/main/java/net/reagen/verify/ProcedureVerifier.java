package net.reagen.verify;

import net.reagen.ast.*;
import net.reagen.codegen.ElementVisitor;
import net.reagen.codegen.ProcedureGenerator;

import java.util.List;

/**
 * Верификатор процедур (реализация идеи "проверка прямо из редактора" на новом домене):
 *  1) структурные правила графа (висячие ссылки, неизвестные блоки);
 *  2) типизация переменных (использование необъявленных, несопадение типов в setvar/return);
 *  3) генерируемость: компилируем ли мы код без исключений ElementVisitor;
 *  4) синтаксис Java: скобки/кавычки + (опционально) javac -proc:none через TempDir.
 */
public class ProcedureVerifier {

    public VerifyReport verify(Procedure p) {
        VerifyReport r = new VerifyReport();

        // --- структурные проверки
        if (p.name == null || p.name.isBlank()) r.error("root", "NAME_EMPTY", "Имя процедуры пустое");
        else if (!p.name.matches("[a-z][a-zA-Z0-9_]*")) r.error("root", "NAME_FORMAT", "Имя должно быть lowerCamelCase: " + p.name);
        if (p.rootId == null || p.byId(p.rootId) == null) r.error("root", "NO_ROOT", "Нет стартового блока rootId");

        for (ProcedureElement el : p.elements.values()) {
            if (el.outputNext != null && p.byId(el.outputNext) == null)
                r.error(el.id, "DANGLING_NEXT", "Ссылка outputNext ведёт в никуда: " + el.outputNext);
            for (ProcedureElement.Input in : el.inputs) {
                if (in.connectedElementId != null && p.byId(in.connectedElementId) == null)
                    r.error(el.id, "DANGLING_INPUT", "Вход " + in.name + " ссылается на несуществующий блок: " + in.connectedElementId);
            }
            for (List<ProcedureElement> br : el.branches) {
                for (ProcedureElement b : br) {
                    if (b == null) { r.error(el.id, "BROKEN_BRANCH", "Повреждённая ветка"); continue; }
                    if (!p.elements.containsKey(b.id)) r.error(el.id, "BRANCH_OUTSIDE", "Блок ветки " + b.id + " отсутствует в elements[]");
                }
            }
        }

        // --- типизация и генерируемость
        Scope scope = p.buildScope();
        try {
            ElementVisitor ev = new ElementVisitor();
            ElementVisitor.FragmentCollector sink = new ElementVisitor.FragmentCollector();
            List<String> body = ev.traverse(p, p.byId(p.rootId), scope, sink);
            // проверка возврата: есть ли return нужного типа если returnType != void
            boolean hasReturn = body.stream().anyMatch(s -> s.startsWith("return "));
            if (p.returnType != McType.LOGIC && !hasReturn)
                r.warn(p.rootId, "MISSING_RETURN", "Процедура с returnType=" + p.returnType + " не содержит явного return");
        } catch (RuntimeException e) {
            r.error(p.rootId, "UNGENERABLE", "Граф не может быть преобразован в код: " + e.getMessage());
        }

        // --- синтаксис generated Java (эвристики + javac если доступен)
        try {
            String code = new ProcedureGenerator().generate(p);
            r.setGeneratedJava(code);
            checkBraces(code, r);
            String javacErr = JavacCheck.tryCompile(code, p.name);
            if (javacErr != null) r.error("generated", "JAVAC", "Сгенерированный код не компилируется: " + javacErr);
        } catch (RuntimeException e) {
            // уже поймано выше как UNGENERABLE
        }

        // --- headless-геймплей симуляция ("игровой тест" без запуска Minecraft):
        // исполняем граф на мок-контексте и ловим рантайм-дефекты логики.
        // Пропускаем заведомо битые графы — их уже описали структурные правила.
        if (r.valid()) {
            new GameSimulator().simulate(p, r);
        }
        return r;
    }

    private void checkBraces(String code, VerifyReport r) {
        int bal = 0; boolean inStr = false; char prev = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inStr) { if (c == '"' && prev != '\\') inStr = false; }
            else if (c == '"') inStr = true;
            else if (c == '{') bal++;
            else if (c == '}') bal--;
            prev = c;
        }
        if (bal != 0) r.error("generated", "SYNTAX_BRACES", "Разбалансированы фигурные скобки в генерируемом коде: " + bal);
    }
}
