# SQLGen Studio — Архитектура проекта

IDE-подобный редактор для визуального построения SQL-процедур и генерации кода
(Rust/Tauri frontend + Java backend-подложка + база процедур).

## Структура

```
/workspace
├── docs/                     # Документация (архитектура, ADR по проверке процедур)
├── db/
│   ├── migrations/           # DDL: таблицы метаданных процедур
│   └── procedures/           # Готовые .sql процедуры (источник истины для примеров)
├── java-backend/sqlgen/      # Java-подложка: AST, парсер, генератор кода, верификатор
│   └── src/main/java/org/sqlgen/{ast,parser,codegen,verify}
├── editor/
│   ├── src-tauri/            # Rust-часть: Tauri-команды (мост к Java через JNI/process)
│   └── src/                  # UI редактора (TypeScript/Svelte-заготовки)
```

## Потоки данных

1. **Редактор (Rust/Tauri)** собирает граф шагов процедуры → JSON спецификация.
2. **Java подложка** десериализует JSON в `Procedure` AST → прогоняет `Verifier`
   (статические правила) → `CodeGenerator` выдаёт диалект-SQL (PostgreSQL / MySQL).
3. **Проверка из редактора**: см. `docs/ADR-procedure-verification.md` —
   выбранный подход: офлайн-парсинг (JSqlParser) + статические правила + опциональный
   dry-run на транзакции с rollback (`PREPARE`/`EXPLAIN`), вызывается из Rust как
   внешний процесс `java -jar verifier.jar`.
