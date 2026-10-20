-- 002_sample_business_tables.sql
-- Пример бизнес-схемы, на которой верификатор проверяет ссылки на таблицы/колонки.

CREATE TABLE IF NOT EXISTS customers (
    id         BIGSERIAL PRIMARY KEY,
    email      TEXT NOT NULL UNIQUE,
    full_name  TEXT NOT NULL,
    balance    NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT NOT NULL REFERENCES customers(id),
    amount       NUMERIC(12,2) NOT NULL,
    status       TEXT NOT NULL DEFAULT 'new',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGSERIAL PRIMARY KEY,
    entity     TEXT NOT NULL,
    entity_id  BIGINT NOT NULL,
    action     TEXT NOT NULL,
    details    JSONB,
    logged_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
