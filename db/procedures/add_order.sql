-- procedures/add_order.sql
-- Пример процедуры с транзакцией и обновлением баланса.

CREATE OR REPLACE FUNCTION add_order(p_customer_id BIGINT, p_amount NUMERIC(12,2))
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_id BIGINT;
BEGIN
    IF p_amount <= 0 THEN
        RAISE EXCEPTION 'amount must be positive';
    END IF;

    INSERT INTO orders (customer_id, amount, status)
    VALUES (p_customer_id, p_amount, 'new')
    RETURNING id INTO v_order_id;

    UPDATE customers
       SET balance = balance - p_amount
     WHERE id = p_customer_id;

    INSERT INTO audit_log (entity, entity_id, action, details)
    VALUES ('orders', v_order_id, 'created',
            jsonb_build_object('amount', p_amount));

    RETURN v_order_id;
END;
$$;
