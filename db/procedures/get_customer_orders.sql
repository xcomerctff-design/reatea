-- procedures/get_customer_orders.sql
-- Пример процедуры, генерируемой редактором (PostgreSQL dialect).

CREATE OR REPLACE FUNCTION get_customer_orders(p_customer_id BIGINT)
RETURNS TABLE(order_id BIGINT, amount NUMERIC(12,2), status TEXT)
LANGUAGE plpgsql
AS $$
BEGIN
    -- шаг 1: проверить существование клиента
    IF NOT EXISTS (SELECT 1 FROM customers c WHERE c.id = p_customer_id) THEN
        RAISE EXCEPTION 'customer % not found', p_customer_id;
    END IF;

    -- шаг 2: вернуть заказы
    RETURN QUERY
        SELECT o.id, o.amount, o.status
        FROM orders o
        WHERE o.customer_id = p_customer_id
        ORDER BY o.created_at DESC;

    -- шаг 3: аудит
    INSERT INTO audit_log (entity, entity_id, action)
    VALUES ('customers', p_customer_id, 'read_orders');
END;
$$;
