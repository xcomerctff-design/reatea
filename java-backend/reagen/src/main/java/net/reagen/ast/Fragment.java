package net.reagen.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Порождающий фрагмент кода (аналог com.mcreator.java.fragment.CodeFragment из MCreator).
 * Fragment знает, КАКОЙ код он генерирует и КАКИЕ импорты/поля/конструктор-код ему нужны.
 * Композиция: дерево Fragments обходится ElementVisitor'ом и собирается в итоговый .java файл.
 */
public abstract class Fragment {
    /** Импорты, необходимые этому фрагменту (полные FQN). */
    public final List<String> imports = new ArrayList<>();
    /** Поля класса, которые фрагмент хочет добавить вProcedure-класс. */
    public final List<String> fields = new ArrayList<>();
    /** Код, добавляемый в static-инициализатор (регистрация зависимостей). */
    public final List<String> initCode = new ArrayList<>();
    /** Имена локальных переменных, объявляемых этим фрагментом. */
    public final List<String> declaredLocals = new ArrayList<>();

    /** Сгенерировать statements этого фрагмента внутри execute-execute body. */
    public abstract List<String> generateStatements(Scope scope);

    /** Требуемые зависимости для отрисовки в редакторе (для валидации графа). */
    public List<String> requiredDependencies() { return List.of(); }

    protected void imp(String... fqns) { for (String f : fqns) if (!imports.contains(f)) imports.add(f); }
}
