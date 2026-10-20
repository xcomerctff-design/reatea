# ReaT — визуальный редактор процедур в стиле MCreator + кодогенерация Minecraft-модов (Forge 1.20.1)

Проект переосмыслен: **процедуры — это не SQL**, а визуальные алгоритмы (как Procedures в MCreator 2024.4).
Редактор строит типизированный граф блоков, Rust-ядро отправляет его на Java-подложку, которая
**только проверяет** код (не исполняет пользовательский код в JVM) и генерирует Java-классы мода для **Minecraft Forge 1.20.1**.

## Структура
- `procedures/` — примеры процедур в JSON-формате (граф элементов, Blockly-подобный контракт)
- `java-backend/reagen/` — Java: модель процедуры (AST), парсер графа, ElementVisitor + ProcedureGenerator (кодоген Forge 1.20.1), верификатор из 4 уровней, стабы MC/Forge API для офлайн-компиляции
- `editor/core/` — Rust-ядро `reagen-core`: serde-модели (единый camelCase-контракт UI↔Rust↔Java) + резидентный мост к JVM-верификатору (daemon, stdin/stdout JSON + EOT-маркер)
- `docs/` — архитектура конвейера проверки и ADR
- `scripts/build.sh` — полный цикл: javac → smoke-тесты → jar → cargo test → E2E через VerifyCli

## Конвейер проверки («игровой тест» без запуска игры)
UI-граф (JSON) → Rust-мост → Java-парсер → **L1 структура графа** (висящие ссылки, корень, достижимость)
→ **L2 семантика/типы** (McType, области видимости переменных, совместимость входов)
→ **L3 компилируемость** (ElementVisitor → `<Name>Procedure.java` → javac против стабов API 1.20.1)
→ **L4 headless-симуляция** (`GameSimulator`: мок-мир/MockPlayer/MockItemStack, трассировка chat/spawn/give,
ловля деления на ноль, спавна неизвестных сущностей, пустых ItemStack, циклов flow).

Ограничение L4: симулятор воспроизводит локальную семантику выражений, но НЕ заменяет запуск игры —
физика, тик-порядок событий и клиент-серверная репликация вне зоны гарантий.

## Поддерживаемые блоки процедур (v0.2)
- Поток: `controls_if` (+ else-if/else), `controls_forEach`, `procedures_setvar`, `return_logic/number/string`, вызов процедур
- Выражения: `math_number`, `text`, `logic_boolean`, `logic_compare`, `logic_operation`, `not`, `math_arithmetic`, `variables_get`, `itemstack_count`
- Minecraft: `mc_sendmessage`, `mc_spawnentity`, `mc_give_item_to_player`, `entity_getpos/coord_x/y/z`,
  `mc_getitemfromslot` (чтение слота инвентаря игрока), `itemstack_make` (сборка стака: id + количество)

## Статус сборки (текущий)
- Java SmokeRunner: **19 PASS / 0 FAIL** (генерация + все 4 уровня проверки)
- Rust `cargo test`: **2 passed**
- E2E `procedures/give_diamond.json` через VerifyCli daemon: `{"valid":true,"issues":[]}`
- Релиз: см. GitHub Releases (reagen.jar + tar.gz, linux-протестирован; windows-rиски: пути/кодировки — TODO CI matrix)
