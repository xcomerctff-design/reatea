# Архитектура ReaT

## Концепция
**Процедуры — это визуальные алгоритмы** (как Procedures в MCreator 2024.4, по принципу Unreal Blueprints):
Blockly-подобный граф блоков с типизированными входами/выходами. Никакого SQL в проекте нет.

Разделение ответственности (ключевое решение):
* **Rust/Tauri (редактор)** — единственный владелец состояния: хранение проектов, редактирование графа,
  интерпретация предпросмотра, экспорт мода. Отправляет в Java **готовый граф процедуры**.
* **Java-подложка (`java-backend/reagen`) — ТОЛЬКО проверка присланного из редактора кода.**
  Ничего не хранит, ничего не исполняет за пределами валидации.

## Конвейер проверки («Проверить» прямо из редактора) — 4 уровня
```
UI-граф (TS) ──invoke──> Tauri ──> reagen-core (Rust)
   │ сериализация Procedure JSON (camelCase-контракт)
   ▼
resident JVM: java -cp reagen.jar net.reagen.verify.VerifyCli --daemon
   │ фрейминг stdin/stdout JSON + маркер %%EOT%%
   ▼
ProcedureVerifier:
  L1 Структура графа ....... DANGLING_NEXT / DANGLING_INPUT / BROKEN_BRANCH / NO_ROOT / NAME_*
  L2 Семантика и типы ...... необъявленные переменные, совместимость McType, MISSING_RETURN
  L3 Компилируемость ....... генерация <Name>Procedure.java (Forge 1.20.1 API-стабы) → javac (JAVAC/SYNTAX_BRACES)
  L4 Headless-симуляция .... GameSimulator: «игровой тест» без Minecraft (см. ниже)
   ▼
VerifyReport {valid, issues[{elementId, severity, rule, message}]}
   → Rust маппит elementId на узлы графа → подсветка в UI
```

## Уровень 4: можно ли узнать «будет ли работать», не заходя в игру?
`GameSimulator` интерпретирует сам граф (не байткод) на мок-контексте мира
(MockLevel/MockPlayer/MockItemStack, реестр vanilla-типов сущностей) и ловит рантайм-дефекты:
* `SIM_DIV_BY_ZERO`, `SIM_NAN_POSITION`, `SIM_TYPE_MISMATCH`, `SIM_BAD_LITERAL`;
* `SIM_UNKNOWN_ENTITY` (спавн типа, которого нет в реестре — в игре `EntityType.byString(...).orElse(null)`);
* `SIM_EMPTY_MESSAGE`, `SIM_GIVE_EMPTY_STACK`, `SIM_UNDECLARED_VAR`;
* `SIM_FLOW_CYCLE`, `SIM_STEP_LIMIT` (защита от циклических связок outputNext).

Что симуляция **не** гарантирует: физику движения, тик-порядок Forge-событий, баланс, клиент/сервер-репликации,
поведение сторонних модов. Поэтому она — последний эшелон перед сборкой, а не замена запуска игры.

## Формат процедуры (JSON, camelCase = единый контракт UI↔Rust↔Java)
См. `procedures/give_diamond.json`, `procedures/low_hp_regen.json`. Поля:
`name, returnType(number|string|logic|itemstack|entity|world), rootId, args[], locals[], elements[]`;
элемент: `id, type, x, y, outputNext, fields[{name,value}], inputs[{name,shadowType,connectedElementId}], branches[[id...]]`.

## Генерация (тоже Java, но это часть проверки «компилируемости»)
`ProcedureGenerator` собирает класс `<Name>Procedure` c сигнатурой
`public static <T> execute(Level world, double x, double y, double z, Entity _ent, ...args)` —
в стиле MCreator; overload `execute(world,x,y,z)` генерируется только для процедур без args.

## Почему не альтернативы
* Исполнять пользовательский Java-код в песочнице (JavaCompiler+ClassLoader) — отказ: небезопасно и не нужно:
  нам важна проверка, а не произвольная логика; поведение проверяется интерпретацией графа (L4).
* Интерпретатор на Rust — дублирование семантики и риск рассинхрона с тем, что компилирует javac-уровень.
* Реальный запуск Minecraft (runServer) — самый точный, но секунды-минуты на цикл правки; оставлен как внешний CI-шаг.

## Сборка
`./scripts/build.sh` — javac → SmokeRunner (18 проверок) → reagen.jar → cargo test → E2E VerifyCli.
