# SQLGen Studio

Редактор визуальной сборки SQL-процедур с генерацией кода (PostgreSQL/MySQL)
и **проверкой процедур прямо из редактора** через Java-подложку.

## Быстрый старт
```bash
./scripts/build.sh        # компиляция Java + jar + smoke-тесты + cargo test Rust core
# Ручная проверка верификатора (протокол, который использует Rust-мост):
java -cp java-backend/sqlgen/out org.sqlgen.verify.VerifyMain --schema db/migrations/002_sample_business_tables.sql < spec.json
```

## Структура
- `docs/` — ARCHITECTURE.md и ADR-001 (анализ подходов проверки процедур, выбор решения)
- `db/migrations/`, `db/procedures/` — схема реестра процедур и примеры SQL
- `java-backend/sqlgen/` — AST, JSON-парсер, генераторы кода, верификатор, CLI VerifyMain
- `editor/core/` — Rust crate: модели ProcedureSpec/Report + JavaVerifier (daemon-мост), тесты
- `editor/src-tauri/` — Tauri-команды verify_procedure / generate_code
- `editor/src/` — TS-заготовки UI (api.ts, VerifyPanel.ts)
- `scripts/build.sh` — сборка и прогон всех тестов

## Как работает проверка из редактора
Кнопка «Проверить» → Tauri invoke `verify_procedure(spec)` → resident JVM-процесс
(`VerifyMain --daemon`, stdin/stdout JSON, EOT-фрейминг) → статические правила +
сверка с DDL-моделью схемы → отчёт `{valid, errors[stepIndex,line,rule,message], generatedSql}`
→ подсветка ошибок на узлах графа. Опционально — dry-run EXPLAIN на тестовой БД
(интерфейс `Verifier.DsnChecker`). Обоснование выбора — в docs/ADR-procedure-verification.md.
