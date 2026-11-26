-- =============================================================================
-- ZENITH FINANCE — MIGRATION 003: TRANSACTION DELETE & CLEAR LEDGER UPGRADE
-- Idempotent upgrade query for existing Supabase databases:
-- 1. Guarantees Personal DELETE/UPDATE RLS policies on transactions, budgets,
--    savings_goals, and auto_pay_reminders.
-- 2. Fixes family_transactions_update WITH CHECK so deletions succeed even if
--    historical paid_by members later left the family.
-- 3. Adds atomic SECURITY DEFINER RPCs so clearing / deleting records cleans
--    Supabase PostgreSQL immediately and prevents automatic sync regain.
-- =============================================================================

-- 1. Personal Ledger RLS Delete / Soft-Delete Guarantees
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.savings_goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auto_pay_reminders ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "transactions_select" ON public.transactions;
CREATE POLICY "transactions_select" ON public.transactions
    FOR SELECT TO authenticated
    USING (user_id = auth.uid() AND NOT is_deleted);

DROP POLICY IF EXISTS "transactions_insert" ON public.transactions;
CREATE POLICY "transactions_insert" ON public.transactions
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "transactions_update" ON public.transactions;
CREATE POLICY "transactions_update" ON public.transactions
    FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "transactions_delete" ON public.transactions;
CREATE POLICY "transactions_delete" ON public.transactions
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

DROP POLICY IF EXISTS "budgets_delete" ON public.budgets;
CREATE POLICY "budgets_delete" ON public.budgets
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

DROP POLICY IF EXISTS "savings_goals_delete" ON public.savings_goals;
CREATE POLICY "savings_goals_delete" ON public.savings_goals
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

DROP POLICY IF EXISTS "auto_pay_reminders_delete" ON public.auto_pay_reminders;
CREATE POLICY "auto_pay_reminders_delete" ON public.auto_pay_reminders
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

-- 2. Family Transaction Update / Delete RLS Hardening (applied safely if Family Mode tables exist)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'family_transactions'
    ) THEN
        EXECUTE 'DROP POLICY IF EXISTS "family_transactions_update" ON public.family_transactions';
        EXECUTE $p$
            CREATE POLICY "family_transactions_update" ON public.family_transactions
                FOR UPDATE TO authenticated
                USING (
                    public.is_family_member(family_id)
                    AND (created_by = auth.uid() OR public.is_family_admin(family_id))
                )
                WITH CHECK (
                    public.is_family_member(family_id)
                    AND (created_by = auth.uid() OR public.is_family_admin(family_id))
                    AND (is_deleted = TRUE OR paid_by IS NULL OR public.is_user_active_member_of(paid_by, family_id))
                )
        $p$;

        EXECUTE 'DROP POLICY IF EXISTS "family_transactions_delete" ON public.family_transactions';
        EXECUTE $p$
            CREATE POLICY "family_transactions_delete" ON public.family_transactions
                FOR DELETE TO authenticated
                USING (
                    public.is_family_member(family_id)
                    AND (created_by = auth.uid() OR public.is_family_admin(family_id))
                )
        $p$;
    END IF;
END $$;

-- 3. Single Personal Transaction Delete RPC
CREATE OR REPLACE FUNCTION public.delete_my_personal_transaction(p_tx_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_deleted INT := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required.';
    END IF;
    DELETE FROM public.transactions WHERE id = p_tx_id AND user_id = v_uid;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    IF v_deleted = 0 THEN
        UPDATE public.transactions
        SET is_deleted = TRUE, updated_at = NOW()
        WHERE id = p_tx_id AND user_id = v_uid;
        GET DIAGNOSTICS v_deleted = ROW_COUNT;
    END IF;
    RETURN v_deleted > 0;
END;
$$;

-- 4. Clear Personal Cloud Ledger RPC (Transactions, Budgets, Goals, Reminders)
CREATE OR REPLACE FUNCTION public.clear_my_personal_ledger()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_tx_cnt INT := 0;
    v_budget_cnt INT := 0;
    v_goal_cnt INT := 0;
    v_reminder_cnt INT := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required to clear personal cloud ledger.';
    END IF;

    DELETE FROM public.transactions WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    GET DIAGNOSTICS v_tx_cnt = ROW_COUNT;

    DELETE FROM public.budgets WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    GET DIAGNOSTICS v_budget_cnt = ROW_COUNT;

    DELETE FROM public.savings_goals WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    GET DIAGNOSTICS v_goal_cnt = ROW_COUNT;

    DELETE FROM public.auto_pay_reminders WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    GET DIAGNOSTICS v_reminder_cnt = ROW_COUNT;

    RETURN jsonb_build_object(
        'transactions', v_tx_cnt,
        'budgets', v_budget_cnt,
        'savings_goals', v_goal_cnt,
        'auto_pay_reminders', v_reminder_cnt
    );
END;
$$;

-- 5. Clear Family Transactions RPC (Admin clears all in family; Member clears own)
CREATE OR REPLACE FUNCTION public.clear_family_transactions_atomic(p_family_id UUID)
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_deleted INT := 0;
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_member(p_family_id) THEN
        RAISE EXCEPTION 'Active family membership required.';
    END IF;

    IF public.is_family_admin(p_family_id) THEN
        DELETE FROM public.family_transactions WHERE family_id = p_family_id;
        GET DIAGNOSTICS v_deleted = ROW_COUNT;
        PERFORM public.log_family_audit(
            p_family_id, v_uid, 'FAMILY_TRANSACTIONS_CLEARED', 'family_transactions', p_family_id,
            jsonb_build_object('deleted_count', v_deleted)
        );
    ELSE
        DELETE FROM public.family_transactions WHERE family_id = p_family_id AND created_by = v_uid;
        GET DIAGNOSTICS v_deleted = ROW_COUNT;
        PERFORM public.log_family_audit(
            p_family_id, v_uid, 'MEMBER_OWN_TRANSACTIONS_CLEARED', 'family_transactions', p_family_id,
            jsonb_build_object('deleted_count', v_deleted)
        );
    END IF;

    RETURN v_deleted;
END;
$$;

REVOKE ALL ON FUNCTION
    public.delete_my_personal_transaction(UUID),
    public.clear_my_personal_ledger(),
    public.clear_family_transactions_atomic(UUID)
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
    public.delete_my_personal_transaction(UUID),
    public.clear_my_personal_ledger(),
    public.clear_family_transactions_atomic(UUID)
TO authenticated;
