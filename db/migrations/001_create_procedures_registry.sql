-- 001_create_procedures_registry.sql
-- Реестр процедур, создаваемых в редакторе SQLGen Studio.

CREATE TABLE IF NOT EXISTS procedure_registry (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE,
    dialect      TEXT        NOT NULL DEFAULT 'postgresql'
                           CHECK (dialect IN ('postgresql','mysql')),
    version      INT         NOT NULL DEFAULT 1,
    ast_json     JSONB       NOT NULL,           -- визуальный граф шагов из редактора
    generated_sql TEXT,                          -- результат CodeGenerator
    verified_at  TIMESTAMPTZ,                    -- последняя успешная проверка
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_registry_name ON procedure_registry (name);

CREATE TABLE IF NOT EXISTS verification_log (
    id            BIGSERIAL PRIMARY KEY,
    procedure_id  BIGINT NOT NULL REFERENCES procedure_registry(id) ON DELETE CASCADE,
    ok            BOOLEAN NOT NULL,
    report_json   JSONB  NOT NULL,               -- отчёт VerifyMain
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
