-- =============================================================================
-- ZENITH FINANCE — MIGRATION 002: COMPLETE FAMILY MODE, CHAT & STRICT RLS
-- Targets: PostgreSQL 15+ / Supabase (Idempotent & Atomic)
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- 1. DROP RETIRED / LOCKED POLICIES BEFORE RECONSTRUCTING FAMILY ACCESS
-- =============================================================================
DO $$
DECLARE
    pol record;
BEGIN
    FOR pol IN
        SELECT schemaname, tablename, policyname
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename IN (
              'profiles', 'families', 'family_members', 'family_invitations',
              'family_transactions', 'family_categories', 'family_budgets',
              'family_messages', 'family_audit_logs'
          )
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I.%I', pol.policyname, pol.schemaname, pol.tablename);
    END LOOP;
END $$;

DROP FUNCTION IF EXISTS public.create_family(TEXT);
DROP FUNCTION IF EXISTS public.join_family_by_code(VARCHAR);
DROP FUNCTION IF EXISTS public.join_family_by_code(VARCHAR, TEXT);
DROP FUNCTION IF EXISTS public.get_family_dashboard(UUID);

-- =============================================================================
-- 2. CORE FAMILY TABLES & DEFENSIVE MIGRATIONS
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL DEFAULT '',
    full_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2.1 FAMILIES
CREATE TABLE IF NOT EXISTS public.families (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL DEFAULT 'Family Vault',
    avatar_emoji VARCHAR(32) NOT NULL DEFAULT '🏠',
    family_label TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    owner_id UUID REFERENCES public.profiles(id) ON DELETE RESTRICT,
    created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    monthly_budget_limit NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    idempotency_key UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.families ADD COLUMN IF NOT EXISTS avatar_emoji VARCHAR(32) NOT NULL DEFAULT '🏠';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS family_label TEXT NOT NULL DEFAULT '';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES public.profiles(id) ON DELETE RESTRICT;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS monthly_budget_limit NUMERIC(15, 2) NOT NULL DEFAULT 0.00;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS idempotency_key UUID;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DROP TRIGGER IF EXISTS trg_guard_families_update ON public.families;
UPDATE public.families
SET owner_id = created_by
WHERE owner_id IS NULL AND created_by IS NOT NULL;

UPDATE public.families
SET avatar_emoji = '🏠'
WHERE avatar_emoji IS NULL OR length(trim(avatar_emoji)) = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_families_owner_idempotency
    ON public.families (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 2.2 FAMILY MEMBERS (Strictly 1 active family per authenticated user)
CREATE TABLE IF NOT EXISTS public.family_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL DEFAULT 'Member',
    display_name TEXT NOT NULL DEFAULT 'Member',
    member_label TEXT NOT NULL DEFAULT '',
    avatar_emoji VARCHAR(32) NOT NULL DEFAULT '🙂',
    avatar_color VARCHAR(16) NOT NULL DEFAULT '#10B981',
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS name TEXT NOT NULL DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS member_label TEXT NOT NULL DEFAULT '';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS avatar_emoji VARCHAR(32) NOT NULL DEFAULT '🙂';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS avatar_color VARCHAR(16) NOT NULL DEFAULT '#10B981';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Normalize any pre-existing member statuses/roles before enforcing constraints
DROP TRIGGER IF EXISTS trg_sync_family_member_columns ON public.family_members;
DROP TRIGGER IF EXISTS trg_sync_family_member_state ON public.family_members;
UPDATE public.family_members
SET role = CASE
        WHEN upper(coalesce(role, 'MEMBER')) IN ('OWNER', 'ADMIN', 'MEMBER') THEN upper(role)
        ELSE 'MEMBER'
    END,
    status = CASE
        WHEN NOT coalesce(is_active, TRUE) THEN 'REMOVED'
        WHEN upper(coalesce(status, 'ACTIVE')) IN ('ACTIVE', 'REMOVED', 'LEFT') THEN upper(status)
        ELSE 'ACTIVE'
    END,
    display_name = coalesce(NULLIF(trim(display_name), ''), NULLIF(trim(name), ''), 'Member'),
    name = coalesce(NULLIF(trim(name), ''), NULLIF(trim(display_name), ''), 'Member'),
    avatar_emoji = coalesce(NULLIF(trim(avatar_emoji), ''), '🙂');

-- Remove orphan rows without family_id/user_id and deduplicate historical (family_id, user_id) entries safely
DELETE FROM public.family_members WHERE family_id IS NULL OR user_id IS NULL;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT dup.id AS dup_id, keep.id AS keep_id
        FROM (
            SELECT id, family_id, user_id,
                   row_number() OVER (
                       PARTITION BY family_id, user_id
                       ORDER BY (status = 'ACTIVE' AND is_active = TRUE) DESC,
                                CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END ASC,
                                joined_at DESC NULLS LAST,
                                id ASC
                   ) AS rn
            FROM public.family_members
            WHERE family_id IS NOT NULL AND user_id IS NOT NULL
        ) dup
        JOIN (
            SELECT DISTINCT ON (family_id, user_id) id, family_id, user_id
            FROM public.family_members
            WHERE family_id IS NOT NULL AND user_id IS NOT NULL
            ORDER BY family_id, user_id,
                     (status = 'ACTIVE' AND is_active = TRUE) DESC,
                     CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END ASC,
                     joined_at DESC NULLS LAST,
                     id ASC
        ) keep ON keep.family_id = dup.family_id AND keep.user_id = dup.user_id
        WHERE dup.rn > 1
    ) LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='transactions' AND column_name='paid_by_member_id') THEN
            UPDATE public.transactions SET paid_by_member_id = r.keep_id WHERE paid_by_member_id = r.dup_id;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='savings_contributions' AND column_name='member_id') THEN
            UPDATE public.savings_contributions SET member_id = r.keep_id WHERE member_id = r.dup_id;
        END IF;
        DELETE FROM public.family_members WHERE id = r.dup_id;
    END LOOP;
END $$;

-- Ensure at most one active family per user when upgrading legacy data (keep owner/earliest active row)
WITH ranked_active AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY user_id
               ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END ASC,
                        joined_at ASC NULLS LAST,
                        id ASC
           ) AS rn
    FROM public.family_members
    WHERE user_id IS NOT NULL AND status = 'ACTIVE' AND is_active = TRUE
)
UPDATE public.family_members fm
SET status = 'LEFT', is_active = FALSE, updated_at = NOW()
FROM ranked_active ra
WHERE fm.id = ra.id AND ra.rn > 1;

DO $$
BEGIN
    ALTER TABLE public.family_members DROP CONSTRAINT IF EXISTS chk_family_members_role;
    ALTER TABLE public.family_members ADD CONSTRAINT chk_family_members_role
        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'));

    ALTER TABLE public.family_members DROP CONSTRAINT IF EXISTS chk_family_members_status;
    ALTER TABLE public.family_members ADD CONSTRAINT chk_family_members_status
        CHECK (status IN ('ACTIVE', 'REMOVED', 'LEFT'));
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_members_family_user
    ON public.family_members (family_id, user_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_members_active_user
    ON public.family_members (user_id)
    WHERE status = 'ACTIVE' AND is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_family_members_family_active
    ON public.family_members (family_id, role)
    WHERE status = 'ACTIVE' AND is_active = TRUE;

-- 2.3 FAMILY INVITATIONS
CREATE TABLE IF NOT EXISTS public.family_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    invited_email TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL DEFAULT '',
    invite_code VARCHAR(32) NOT NULL,
    invite_token UUID NOT NULL DEFAULT gen_random_uuid(),
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    invited_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    accepted_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    accepted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS invited_email TEXT NOT NULL DEFAULT '';
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS email TEXT NOT NULL DEFAULT '';
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS invite_code VARCHAR(32);
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS invite_token UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS invited_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS accepted_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days');
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.family_invitations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.family_invitations ALTER COLUMN email SET DEFAULT '';
ALTER TABLE public.family_invitations ALTER COLUMN invited_email SET DEFAULT '';

DO $$
BEGIN
    ALTER TABLE public.family_invitations DROP CONSTRAINT IF EXISTS chk_family_inv_role;
    ALTER TABLE public.family_invitations ADD CONSTRAINT chk_family_inv_role
        CHECK (role IN ('ADMIN', 'MEMBER'));

    ALTER TABLE public.family_invitations DROP CONSTRAINT IF EXISTS chk_family_inv_status;
    ALTER TABLE public.family_invitations ADD CONSTRAINT chk_family_inv_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'));
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

UPDATE public.family_invitations
SET invite_code = 'FAM-' || upper(substring(replace(gen_random_uuid()::text, '-', ''), 1, 6))
WHERE invite_code IS NULL OR length(trim(invite_code)) = 0;

WITH dup_inv_code AS (
    SELECT id, row_number() OVER (PARTITION BY upper(invite_code) ORDER BY created_at DESC NULLS LAST, id ASC) AS rn
    FROM public.family_invitations
)
UPDATE public.family_invitations fi
SET invite_code = 'FAM-' || upper(substring(replace(gen_random_uuid()::text, '-', ''), 1, 6))
FROM dup_inv_code d
WHERE fi.id = d.id AND d.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_invitations_code ON public.family_invitations (upper(invite_code));
CREATE UNIQUE INDEX IF NOT EXISTS uq_family_invitations_token ON public.family_invitations (invite_token);
CREATE INDEX IF NOT EXISTS idx_family_invitations_family_status ON public.family_invitations (family_id, status);

-- 2.4 FAMILY CATEGORIES
CREATE TABLE IF NOT EXISTS public.family_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 60),
    icon_name TEXT NOT NULL DEFAULT 'Category',
    color_hex VARCHAR(16) NOT NULL DEFAULT '#10B981',
    type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE' CHECK (type IN ('EXPENSE', 'INCOME')),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_categories_active
    ON public.family_categories (family_id, lower(trim(name)), type)
    WHERE NOT is_deleted;

-- 2.5 FAMILY TRANSACTIONS
CREATE TABLE IF NOT EXISTS public.family_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    paid_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_by_name TEXT NOT NULL DEFAULT 'Member',
    paid_by_name TEXT NOT NULL DEFAULT 'Member',
    transaction_type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE' CHECK (transaction_type IN ('EXPENSE', 'INCOME')),
    amount NUMERIC(15, 2) NOT NULL CHECK (amount > 0),
    category_id UUID REFERENCES public.family_categories(id) ON DELETE SET NULL,
    category_name TEXT NOT NULL DEFAULT 'Other',
    title TEXT NOT NULL DEFAULT 'Transaction',
    description TEXT NOT NULL DEFAULT '',
    custom_label TEXT NOT NULL DEFAULT '',
    payment_method TEXT NOT NULL DEFAULT 'UPI',
    upi_id TEXT,
    upi_transaction_id TEXT,
    transaction_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_tx_id UUID NOT NULL DEFAULT gen_random_uuid(),
    sync_version BIGINT NOT NULL DEFAULT 1,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.family_transactions ADD COLUMN IF NOT EXISTS custom_label TEXT NOT NULL DEFAULT '';

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_transactions_client_id
    ON public.family_transactions (family_id, client_tx_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_transactions_utr
    ON public.family_transactions (family_id, upi_transaction_id)
    WHERE upi_transaction_id IS NOT NULL AND upi_transaction_id <> '' AND NOT is_deleted;

CREATE INDEX IF NOT EXISTS idx_family_transactions_timeline
    ON public.family_transactions (family_id, transaction_date DESC)
    WHERE NOT is_deleted;

CREATE INDEX IF NOT EXISTS idx_family_transactions_paid_by
    ON public.family_transactions (family_id, paid_by)
    WHERE NOT is_deleted;

CREATE INDEX IF NOT EXISTS idx_family_transactions_created_by
    ON public.family_transactions (family_id, created_by);

-- 2.6 FAMILY BUDGETS (Common Monthly & Category Limits for the Family)
CREATE TABLE IF NOT EXISTS public.family_budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    category_id UUID REFERENCES public.family_categories(id) ON DELETE SET NULL,
    category_name TEXT NOT NULL DEFAULT 'Overall',
    monthly_limit NUMERIC(15, 2) NOT NULL CHECK (monthly_limit > 0),
    month_year VARCHAR(7) NOT NULL DEFAULT to_char(NOW(), 'YYYY-MM'),
    period_type VARCHAR(32) NOT NULL DEFAULT 'MONTHLY',
    updated_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_budgets_active
    ON public.family_budgets (family_id, lower(trim(category_name)), month_year, period_type)
    WHERE NOT is_deleted;

-- 2.7 FAMILY MESSAGES (WhatsApp-style Family Group Chat)
CREATE TABLE IF NOT EXISTS public.family_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    sender_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    sender_name TEXT NOT NULL DEFAULT 'Member',
    sender_avatar VARCHAR(32) NOT NULL DEFAULT '🙂',
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' CHECK (message_type IN ('TEXT', 'EXPENSE_SHARE', 'SYSTEM')),
    body TEXT NOT NULL CHECK (length(trim(body)) BETWEEN 1 AND 2000),
    related_transaction_id UUID REFERENCES public.family_transactions(id) ON DELETE SET NULL,
    reply_to_message_id UUID REFERENCES public.family_messages(id) ON DELETE SET NULL,
    client_msg_id UUID NOT NULL DEFAULT gen_random_uuid(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_messages_client_id
    ON public.family_messages (family_id, client_msg_id);

CREATE INDEX IF NOT EXISTS idx_family_messages_timeline
    ON public.family_messages (family_id, created_at DESC)
    WHERE NOT is_deleted;

-- 2.8 FAMILY AUDIT LOGS
CREATE TABLE IF NOT EXISTS public.family_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    entity_name TEXT NOT NULL,
    entity_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_family_audit_logs_family_date
    ON public.family_audit_logs (family_id, created_at DESC);

-- =============================================================================
-- 3. NON-RECURSIVE SECURITY DEFINER HELPERS
-- =============================================================================

CREATE OR REPLACE FUNCTION public.is_family_member(p_family_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.family_members fm
        JOIN public.families f ON f.id = fm.family_id
        WHERE fm.family_id = p_family_id
          AND fm.user_id = auth.uid()
          AND fm.status = 'ACTIVE'
          AND fm.is_active = TRUE
          AND NOT f.is_deleted
    );
$$;

CREATE OR REPLACE FUNCTION public.is_family_admin(p_family_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.family_members fm
        JOIN public.families f ON f.id = fm.family_id
        WHERE fm.family_id = p_family_id
          AND fm.user_id = auth.uid()
          AND fm.status = 'ACTIVE'
          AND fm.is_active = TRUE
          AND fm.role IN ('OWNER', 'ADMIN')
          AND NOT f.is_deleted
    );
$$;

CREATE OR REPLACE FUNCTION public.is_family_owner(p_family_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.families f
        JOIN public.family_members fm ON fm.family_id = f.id
        WHERE f.id = p_family_id
          AND NOT f.is_deleted
          AND fm.user_id = auth.uid()
          AND fm.status = 'ACTIVE'
          AND fm.is_active = TRUE
          AND (fm.role = 'OWNER' OR f.owner_id = auth.uid())
    );
$$;

CREATE OR REPLACE FUNCTION public.is_user_active_member_of(p_user_id UUID, p_family_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.family_members fm
        WHERE fm.family_id = p_family_id
          AND fm.user_id = p_user_id
          AND fm.status = 'ACTIVE'
          AND fm.is_active = TRUE
    );
$$;

CREATE OR REPLACE FUNCTION public.shares_active_family_with(p_other_user_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.family_members me
        JOIN public.family_members other ON other.family_id = me.family_id
        JOIN public.families f ON f.id = me.family_id
        WHERE me.user_id = auth.uid()
          AND other.user_id = p_other_user_id
          AND me.status = 'ACTIVE' AND me.is_active = TRUE
          AND other.status = 'ACTIVE' AND other.is_active = TRUE
          AND NOT f.is_deleted
    );
$$;

CREATE OR REPLACE FUNCTION public.log_family_audit(
    p_family_id UUID,
    p_actor_user_id UUID,
    p_action TEXT,
    p_entity_name TEXT,
    p_entity_id UUID,
    p_metadata JSONB DEFAULT '{}'::jsonb
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.family_audit_logs (family_id, actor_user_id, action, entity_name, entity_id, metadata)
    VALUES (p_family_id, p_actor_user_id, p_action, p_entity_name, p_entity_id, coalesce(p_metadata, '{}'::jsonb));
END;
$$;

CREATE OR REPLACE FUNCTION public.seed_family_default_categories(p_family_id UUID, p_creator_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.family_categories (family_id, name, icon_name, color_hex, type, is_default, created_by)
    SELECT p_family_id, val.name, val.icon_name, val.color_hex, val.type, TRUE, p_creator_id
    FROM (
        VALUES
            ('Food & Dining', 'Restaurant', '#F59E0B', 'EXPENSE'),
            ('Groceries', 'ShoppingCart', '#10B981', 'EXPENSE'),
            ('Housing & Rent', 'Home', '#6366F1', 'EXPENSE'),
            ('Bills & Utilities', 'Receipt', '#EF4444', 'EXPENSE'),
            ('Transportation', 'DirectionsCar', '#3B82F6', 'EXPENSE'),
            ('Shopping', 'ShoppingBag', '#EC4899', 'EXPENSE'),
            ('Healthcare', 'LocalHospital', '#14B8A6', 'EXPENSE'),
            ('Education', 'School', '#8B5CF6', 'EXPENSE'),
            ('Entertainment', 'Movie', '#06B6D4', 'EXPENSE'),
            ('Family Income', 'AttachMoney', '#10B981', 'INCOME'),
            ('Other', 'Category', '#64748B', 'EXPENSE')
    ) AS val(name, icon_name, color_hex, type)
    WHERE NOT EXISTS (
        SELECT 1
        FROM public.family_categories fc
        WHERE fc.family_id = p_family_id
          AND lower(trim(fc.name)) = lower(trim(val.name))
          AND fc.type = val.type
          AND NOT fc.is_deleted
    );
END;
$$;

-- =============================================================================
-- 4. INTEGRITY & PRIVILEGE ESCALATION GUARD TRIGGERS
-- =============================================================================

CREATE OR REPLACE FUNCTION public.sync_family_member_state()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.display_name := coalesce(NULLIF(trim(NEW.display_name), ''), NULLIF(trim(NEW.name), ''), 'Member');
    NEW.name := NEW.display_name;
    NEW.avatar_emoji := coalesce(NULLIF(trim(NEW.avatar_emoji), ''), '🙂');

    IF NEW.status IN ('REMOVED', 'LEFT') THEN
        NEW.is_active := FALSE;
    ELSIF NEW.is_active = FALSE THEN
        NEW.status := coalesce(NULLIF(NEW.status, 'ACTIVE'), 'REMOVED');
    ELSE
        NEW.status := 'ACTIVE';
        NEW.is_active := TRUE;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.family_id <> OLD.family_id OR NEW.user_id <> OLD.user_id THEN
            RAISE EXCEPTION 'Cannot reassign family_id or user_id on family_members';
        END IF;
        -- Protect OWNER rows from non-RPC role/removal tampering
        IF coalesce(current_setting('app.family_rpc_bypass', true), '') <> 'on' THEN
            IF OLD.role = 'OWNER' AND (NEW.role <> OLD.role OR NEW.status <> OLD.status) THEN
                RAISE EXCEPTION 'Use transfer_family_ownership or close_family RPC to modify Owner membership';
            END IF;
            IF NEW.role = 'OWNER' AND OLD.role <> 'OWNER' THEN
                RAISE EXCEPTION 'Use transfer_family_ownership RPC to grant ownership';
            END IF;
        END IF;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_family_member_columns ON public.family_members;
DROP TRIGGER IF EXISTS trg_sync_family_member_state ON public.family_members;
CREATE TRIGGER trg_sync_family_member_state
    BEFORE INSERT OR UPDATE ON public.family_members
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_family_member_state();

CREATE OR REPLACE FUNCTION public.guard_families_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.name := coalesce(NULLIF(trim(NEW.name), ''), OLD.name);
    NEW.avatar_emoji := coalesce(NULLIF(trim(NEW.avatar_emoji), ''), '🏠');

    IF coalesce(current_setting('app.family_rpc_bypass', true), '') <> 'on' AND auth.uid() IS NOT NULL THEN
        IF NEW.owner_id IS DISTINCT FROM OLD.owner_id AND NOT public.is_family_owner(OLD.id) THEN
            RAISE EXCEPTION 'Only family owner can transfer ownership';
        END IF;
        IF NEW.is_deleted = TRUE AND OLD.is_deleted = FALSE AND NOT public.is_family_owner(OLD.id) THEN
            RAISE EXCEPTION 'Only family owner can delete or close the family';
        END IF;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_guard_families_update ON public.families;
CREATE TRIGGER trg_guard_families_update
    BEFORE UPDATE ON public.families
    FOR EACH ROW
    EXECUTE FUNCTION public.guard_families_update();

CREATE OR REPLACE FUNCTION public.guard_family_transaction_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.title := coalesce(NULLIF(trim(NEW.title), ''), NULLIF(trim(NEW.description), ''), 'Transaction');
    NEW.category_name := coalesce(NULLIF(trim(NEW.category_name), ''), 'Other');
    IF NEW.created_by IS NULL AND TG_OP = 'INSERT' THEN
        NEW.created_by := auth.uid();
    END IF;
    IF NEW.paid_by IS NULL THEN
        NEW.paid_by := NEW.created_by;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.family_id <> OLD.family_id OR NEW.created_by <> OLD.created_by THEN
            RAISE EXCEPTION 'Cannot reassign family_id or created_by on family_transactions';
        END IF;
        NEW.sync_version := coalesce(OLD.sync_version, 1) + 1;
    ELSE
        NEW.sync_version := coalesce(NEW.sync_version, 1);
        NEW.created_at := coalesce(NEW.created_at, NOW());
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_guard_family_transaction_row ON public.family_transactions;
CREATE TRIGGER trg_guard_family_transaction_row
    BEFORE INSERT OR UPDATE ON public.family_transactions
    FOR EACH ROW
    EXECUTE FUNCTION public.guard_family_transaction_row();

CREATE OR REPLACE FUNCTION public.guard_family_message_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.family_id <> OLD.family_id OR NEW.sender_user_id <> OLD.sender_user_id THEN
            RAISE EXCEPTION 'Cannot reassign family_id or sender_user_id on family_messages';
        END IF;
    ELSE
        NEW.created_at := coalesce(NEW.created_at, NOW());
    END IF;
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_guard_family_message_row ON public.family_messages;
CREATE TRIGGER trg_guard_family_message_row
    BEFORE INSERT OR UPDATE ON public.family_messages
    FOR EACH ROW
    EXECUTE FUNCTION public.guard_family_message_row();

-- =============================================================================
-- 5. ATOMIC FAMILY WORKFLOW RPCs (CREATE, INVITE, ACCEPT, ROLE, LEAVE, CLOSE)
-- =============================================================================

-- 5.1 Atomic Create Family (Auth required, 1-family limit, Idempotency-safe)
DROP FUNCTION IF EXISTS public.create_family_atomic(TEXT, VARCHAR, TEXT, UUID, NUMERIC);
CREATE OR REPLACE FUNCTION public.create_family_atomic(
    p_name TEXT,
    p_avatar_emoji VARCHAR DEFAULT '🏠',
    p_creator_name TEXT DEFAULT '',
    p_idempotency_key UUID DEFAULT NULL,
    p_monthly_budget_limit NUMERIC DEFAULT 0.00,
    p_family_label TEXT DEFAULT '',
    p_creator_label TEXT DEFAULT '',
    p_creator_emoji VARCHAR DEFAULT '🙂'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_clean_name TEXT := trim(coalesce(p_name, ''));
    v_clean_label TEXT := trim(coalesce(p_family_label, ''));
    v_emoji VARCHAR(32) := coalesce(NULLIF(trim(p_avatar_emoji), ''), '🏠');
    v_creator_emoji VARCHAR(32) := coalesce(NULLIF(trim(p_creator_emoji), ''), '🙂');
    v_creator_label TEXT := trim(coalesce(p_creator_label, ''));
    v_profile RECORD;
    v_display_name TEXT;
    v_existing_family public.families%ROWTYPE;
    v_existing_member RECORD;
    v_family public.families%ROWTYPE;
    v_member public.family_members%ROWTYPE;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required to create a family.';
    END IF;
    IF length(v_clean_name) = 0 OR length(v_clean_name) > 80 THEN
        RAISE EXCEPTION 'Family name must be between 1 and 80 characters.';
    END IF;
    IF coalesce(p_monthly_budget_limit, 0.00) < 0 THEN
        RAISE EXCEPTION 'Monthly budget limit cannot be negative.';
    END IF;

    -- Serialize concurrent requests from the same user inside transaction
    PERFORM pg_advisory_xact_lock(hashtext('user_family_' || v_uid::text));

    -- Return existing family if same idempotency_key is retried
    IF p_idempotency_key IS NOT NULL THEN
        SELECT * INTO v_existing_family
        FROM public.families
        WHERE owner_id = v_uid
          AND idempotency_key = p_idempotency_key
          AND NOT is_deleted
        LIMIT 1;

        IF v_existing_family.id IS NOT NULL THEN
            SELECT * INTO v_member
            FROM public.family_members
            WHERE family_id = v_existing_family.id AND user_id = v_uid
            LIMIT 1;
            RETURN jsonb_build_object(
                'family', to_jsonb(v_existing_family),
                'membership', to_jsonb(v_member)
            );
        END IF;
    END IF;

    -- Enforce 1 active family per user
    SELECT fm.family_id INTO v_existing_member
    FROM public.family_members fm
    WHERE fm.user_id = v_uid AND fm.status = 'ACTIVE' AND fm.is_active = TRUE
    LIMIT 1;

    IF v_existing_member.family_id IS NOT NULL THEN
        RAISE EXCEPTION 'User already belongs to an active family. Leave or close your existing family first.';
    END IF;

    SELECT full_name, email INTO v_profile FROM public.profiles WHERE id = v_uid LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Authenticated user profile not found.';
    END IF;
    v_display_name := coalesce(
        NULLIF(trim(p_creator_name), ''),
        NULLIF(trim(v_profile.full_name), ''),
        NULLIF(split_part(coalesce(v_profile.email, ''), '@', 1), ''),
        'Owner'
    );

    INSERT INTO public.families (
        name, avatar_emoji, family_label, owner_id, created_by, monthly_budget_limit, idempotency_key
    )
    VALUES (
        v_clean_name, v_emoji, v_clean_label, v_uid, v_uid, coalesce(p_monthly_budget_limit, 0.00), p_idempotency_key
    )
    RETURNING * INTO v_family;

    INSERT INTO public.family_members (
        family_id, user_id, name, display_name, member_label, avatar_emoji, role, status, is_active
    )
    VALUES (
        v_family.id, v_uid, v_display_name, v_display_name, v_creator_label, v_creator_emoji, 'OWNER', 'ACTIVE', TRUE
    )
    RETURNING * INTO v_member;

    PERFORM public.seed_family_default_categories(v_family.id, v_uid);

    INSERT INTO public.family_messages (
        family_id, sender_user_id, sender_name, sender_avatar, message_type, body
    )
    VALUES (
        v_family.id, v_uid, v_display_name, v_emoji, 'SYSTEM',
        format('%s created the %s family group.', v_display_name, v_clean_name)
    );

    PERFORM public.log_family_audit(
        v_family.id, v_uid, 'FAMILY_CREATED', 'families', v_family.id,
        jsonb_build_object('name', v_family.name, 'avatar_emoji', v_family.avatar_emoji)
    );

    RETURN jsonb_build_object(
        'family', to_jsonb(v_family),
        'membership', to_jsonb(v_member)
    );
END;
$$;

-- 5.2 Create Scoped Expiring Invitation (Admin/Owner Only)
CREATE OR REPLACE FUNCTION public.create_family_invitation(
    p_family_id UUID,
    p_invited_email TEXT DEFAULT '',
    p_role VARCHAR DEFAULT 'MEMBER',
    p_expires_hours INT DEFAULT 168
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_clean_role VARCHAR(32) := upper(coalesce(nullif(trim(p_role), ''), 'MEMBER'));
    v_code VARCHAR(32);
    v_inv public.family_invitations%ROWTYPE;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required.';
    END IF;
    IF NOT public.is_family_admin(p_family_id) THEN
        RAISE EXCEPTION 'Only family owners or admins can create invitations.';
    END IF;
    IF v_clean_role NOT IN ('ADMIN', 'MEMBER') THEN
        RAISE EXCEPTION 'Invitation role must be ADMIN or MEMBER.';
    END IF;

    -- Expire stale pending invites gracefully
    UPDATE public.family_invitations
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE family_id = p_family_id AND status = 'PENDING' AND expires_at <= NOW();

    v_code := 'FAM-' || upper(substring(replace(gen_random_uuid()::text, '-', ''), 1, 6));

    INSERT INTO public.family_invitations (
        family_id, invited_email, email, invite_code, role, status, invited_by, expires_at
    )
    VALUES (
        p_family_id,
        lower(trim(coalesce(p_invited_email, ''))),
        lower(trim(coalesce(p_invited_email, ''))),
        v_code,
        v_clean_role,
        'PENDING',
        v_uid,
        NOW() + make_interval(hours => GREATEST(1, LEAST(coalesce(p_expires_hours, 168), 720)))
    )
    RETURNING * INTO v_inv;

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'INVITATION_CREATED', 'family_invitations', v_inv.id,
        jsonb_build_object('role', v_clean_role, 'expires_at', v_inv.expires_at)
    );

    RETURN to_jsonb(v_inv);
END;
$$;

-- 5.3 Accept Invitation Atomically (Code or Token; checks expiry & single-family limit)
DROP FUNCTION IF EXISTS public.accept_family_invitation(TEXT, TEXT, VARCHAR);
CREATE OR REPLACE FUNCTION public.accept_family_invitation(
    p_code_or_token TEXT,
    p_display_name TEXT DEFAULT '',
    p_avatar_emoji VARCHAR DEFAULT '🙂',
    p_member_label TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_input TEXT := trim(coalesce(p_code_or_token, ''));
    v_inv public.family_invitations%ROWTYPE;
    v_family public.families%ROWTYPE;
    v_profile RECORD;
    v_member public.family_members%ROWTYPE;
    v_existing_active RECORD;
    v_name TEXT;
    v_emoji VARCHAR(32) := coalesce(NULLIF(trim(p_avatar_emoji), ''), '🙂');
    v_label TEXT := trim(coalesce(p_member_label, ''));
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required to join a family.';
    END IF;
    IF length(v_input) = 0 THEN
        RAISE EXCEPTION 'Invitation code or link token is required.';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('user_family_' || v_uid::text));

    SELECT * INTO v_inv
    FROM public.family_invitations
    WHERE status = 'PENDING'
      AND (
          upper(invite_code) = upper(v_input)
          OR invite_token::text = v_input
      )
    LIMIT 1
    FOR UPDATE;

    IF v_inv.id IS NULL THEN
        RAISE EXCEPTION 'Invitation is invalid or has already been used.';
    END IF;

    IF v_inv.expires_at <= NOW() THEN
        UPDATE public.family_invitations SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_inv.id;
        RAISE EXCEPTION 'Invitation has expired.';
    END IF;

    SELECT * INTO v_family
    FROM public.families
    WHERE id = v_inv.family_id AND NOT is_deleted
    LIMIT 1;

    IF v_family.id IS NULL THEN
        RAISE EXCEPTION 'Target family is no longer active.';
    END IF;

    SELECT email, full_name INTO v_profile FROM public.profiles WHERE id = v_uid LIMIT 1;
    IF length(trim(coalesce(v_inv.invited_email, ''))) > 0
       AND lower(trim(coalesce(v_profile.email, ''))) <> lower(trim(v_inv.invited_email)) THEN
        RAISE EXCEPTION 'This invitation is restricted to another email address.';
    END IF;

    SELECT fm.family_id INTO v_existing_active
    FROM public.family_members fm
    WHERE fm.user_id = v_uid AND fm.status = 'ACTIVE' AND fm.is_active = TRUE
    LIMIT 1;

    IF v_existing_active.family_id IS NOT NULL THEN
        RAISE EXCEPTION 'You already belong to a family. Leave your current family before joining another.';
    END IF;

    v_name := coalesce(
        NULLIF(trim(p_display_name), ''),
        NULLIF(trim(v_profile.full_name), ''),
        NULLIF(split_part(coalesce(v_profile.email, ''), '@', 1), ''),
        'Member'
    );

    INSERT INTO public.family_members (
        family_id, user_id, name, display_name, member_label, avatar_emoji, role, status, is_active, joined_at
    )
    VALUES (
        v_family.id, v_uid, v_name, v_name, v_label, v_emoji, v_inv.role, 'ACTIVE', TRUE, NOW()
    )
    ON CONFLICT (family_id, user_id) DO UPDATE
    SET status = 'ACTIVE',
        is_active = TRUE,
        role = CASE WHEN public.family_members.role = 'OWNER' THEN 'OWNER' ELSE EXCLUDED.role END,
        display_name = EXCLUDED.display_name,
        name = EXCLUDED.name,
        member_label = EXCLUDED.member_label,
        avatar_emoji = EXCLUDED.avatar_emoji,
        joined_at = NOW(),
        updated_at = NOW()
    RETURNING * INTO v_member;

    UPDATE public.family_invitations
    SET status = 'ACCEPTED', accepted_by = v_uid, accepted_at = NOW(), updated_at = NOW()
    WHERE id = v_inv.id;

    INSERT INTO public.family_messages (
        family_id, sender_user_id, sender_name, sender_avatar, message_type, body
    )
    VALUES (
        v_family.id, v_uid, v_name, v_emoji, 'SYSTEM',
        format('%s joined the family.', v_name)
    );

    PERFORM public.log_family_audit(
        v_family.id, v_uid, 'INVITATION_ACCEPTED', 'family_members', v_member.id,
        jsonb_build_object('invitation_id', v_inv.id, 'role', v_member.role)
    );

    RETURN jsonb_build_object(
        'family', to_jsonb(v_family),
        'membership', to_jsonb(v_member)
    );
END;
$$;

-- 5.4 Update Member Role (Owner/Admin only; cannot alter OWNER role)
CREATE OR REPLACE FUNCTION public.update_family_member_role(
    p_family_id UUID,
    p_target_user_id UUID,
    p_new_role VARCHAR
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_clean_role VARCHAR(32) := upper(trim(coalesce(p_new_role, '')));
    v_target public.family_members%ROWTYPE;
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_admin(p_family_id) THEN
        RAISE EXCEPTION 'Only family owner or admin can update member roles.';
    END IF;
    IF v_clean_role NOT IN ('ADMIN', 'MEMBER') THEN
        RAISE EXCEPTION 'Role can only be set to ADMIN or MEMBER here.';
    END IF;

    SELECT * INTO v_target
    FROM public.family_members
    WHERE family_id = p_family_id AND user_id = p_target_user_id AND status = 'ACTIVE' AND is_active = TRUE
    LIMIT 1;

    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'Active member not found.';
    END IF;
    IF v_target.role = 'OWNER' THEN
        RAISE EXCEPTION 'Use transfer_family_ownership to change the family owner.';
    END IF;

    UPDATE public.family_members
    SET role = v_clean_role, updated_at = NOW()
    WHERE id = v_target.id;

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'MEMBER_ROLE_UPDATED', 'family_members', v_target.id,
        jsonb_build_object('target_user_id', p_target_user_id, 'old_role', v_target.role, 'new_role', v_clean_role)
    );
    RETURN TRUE;
END;
$$;

-- 5.5 Remove Member (Admin/Owner; cannot remove Owner)
CREATE OR REPLACE FUNCTION public.remove_family_member(
    p_family_id UUID,
    p_target_user_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_target public.family_members%ROWTYPE;
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_admin(p_family_id) THEN
        RAISE EXCEPTION 'Only family owner or admin can remove members.';
    END IF;

    SELECT * INTO v_target
    FROM public.family_members
    WHERE family_id = p_family_id AND user_id = p_target_user_id AND status = 'ACTIVE' AND is_active = TRUE
    LIMIT 1;

    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'Active member not found.';
    END IF;
    IF v_target.role = 'OWNER' THEN
        RAISE EXCEPTION 'Owner cannot be removed from the family.';
    END IF;

    UPDATE public.family_members
    SET status = 'REMOVED', is_active = FALSE, updated_at = NOW()
    WHERE id = v_target.id;

    INSERT INTO public.family_messages (
        family_id, sender_user_id, sender_name, sender_avatar, message_type, body
    )
    VALUES (
        p_family_id, v_uid, v_target.display_name, v_target.avatar_emoji, 'SYSTEM',
        format('%s was removed from the family.', v_target.display_name)
    );

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'MEMBER_REMOVED', 'family_members', v_target.id,
        jsonb_build_object('target_user_id', p_target_user_id)
    );
    RETURN TRUE;
END;
$$;

-- 5.6 Transfer Ownership (Owner Only)
CREATE OR REPLACE FUNCTION public.transfer_family_ownership(
    p_family_id UUID,
    p_new_owner_user_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_new_owner public.family_members%ROWTYPE;
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_owner(p_family_id) THEN
        RAISE EXCEPTION 'Only the current owner can transfer ownership.';
    END IF;
    IF p_new_owner_user_id = v_uid THEN
        RAISE EXCEPTION 'Selected member is already the owner.';
    END IF;

    SELECT * INTO v_new_owner
    FROM public.family_members
    WHERE family_id = p_family_id AND user_id = p_new_owner_user_id AND status = 'ACTIVE' AND is_active = TRUE
    LIMIT 1;

    IF v_new_owner.id IS NULL THEN
        RAISE EXCEPTION 'Target user must be an active member of this family.';
    END IF;

    PERFORM set_config('app.family_rpc_bypass', 'on', true);

    UPDATE public.family_members
    SET role = 'ADMIN', updated_at = NOW()
    WHERE family_id = p_family_id AND user_id = v_uid AND role = 'OWNER';

    UPDATE public.family_members
    SET role = 'OWNER', updated_at = NOW()
    WHERE id = v_new_owner.id;

    UPDATE public.families
    SET owner_id = p_new_owner_user_id, updated_at = NOW()
    WHERE id = p_family_id;

    INSERT INTO public.family_messages (
        family_id, sender_user_id, sender_name, sender_avatar, message_type, body
    )
    VALUES (
        p_family_id, v_uid, v_new_owner.display_name, v_new_owner.avatar_emoji, 'SYSTEM',
        format('Family ownership transferred to %s.', v_new_owner.display_name)
    );

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'OWNERSHIP_TRANSFERRED', 'families', p_family_id,
        jsonb_build_object('previous_owner', v_uid, 'new_owner', p_new_owner_user_id)
    );
    RETURN TRUE;
END;
$$;

-- 5.7 Explicit Leave Family (Blocks Owner until ownership transfer or family closure)
CREATE OR REPLACE FUNCTION public.leave_family(p_family_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_member public.family_members%ROWTYPE;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required.';
    END IF;

    SELECT * INTO v_member
    FROM public.family_members
    WHERE family_id = p_family_id AND user_id = v_uid AND status = 'ACTIVE' AND is_active = TRUE
    LIMIT 1;

    IF v_member.id IS NULL THEN
        RAISE EXCEPTION 'You are not an active member of this family.';
    END IF;
    IF v_member.role = 'OWNER' THEN
        RAISE EXCEPTION 'Owner must transfer ownership to another member or close the family before leaving.';
    END IF;

    UPDATE public.family_members
    SET status = 'LEFT', is_active = FALSE, updated_at = NOW()
    WHERE id = v_member.id;

    INSERT INTO public.family_messages (
        family_id, sender_user_id, sender_name, sender_avatar, message_type, body
    )
    VALUES (
        p_family_id, v_uid, v_member.display_name, v_member.avatar_emoji, 'SYSTEM',
        format('%s left the family.', v_member.display_name)
    );

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'MEMBER_LEFT', 'family_members', v_member.id, '{}'::jsonb
    );
    RETURN TRUE;
END;
$$;

-- 5.8 Close / Delete Family (Owner Only — Soft Delete Preserving Financial Audit Integrity)
CREATE OR REPLACE FUNCTION public.close_family(p_family_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_owner(p_family_id) THEN
        RAISE EXCEPTION 'Only the family owner can close or delete the family.';
    END IF;

    PERFORM set_config('app.family_rpc_bypass', 'on', true);

    UPDATE public.families
    SET is_deleted = TRUE, updated_at = NOW()
    WHERE id = p_family_id;

    UPDATE public.family_members
    SET status = 'LEFT', is_active = FALSE, updated_at = NOW()
    WHERE family_id = p_family_id AND status = 'ACTIVE';

    UPDATE public.family_invitations
    SET status = 'REVOKED', updated_at = NOW()
    WHERE family_id = p_family_id AND status = 'PENDING';

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'FAMILY_CLOSED', 'families', p_family_id, '{}'::jsonb
    );
    RETURN TRUE;
END;
$$;

-- 5.9 Fetch Active Family Context in One Atomic Query
CREATE OR REPLACE FUNCTION public.get_my_active_family()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_member public.family_members%ROWTYPE;
    v_family public.families%ROWTYPE;
    v_members JSONB;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT fm.* INTO v_member
    FROM public.family_members fm
    JOIN public.families f ON f.id = fm.family_id
    WHERE fm.user_id = v_uid
      AND fm.status = 'ACTIVE'
      AND fm.is_active = TRUE
      AND NOT f.is_deleted
    LIMIT 1;

    IF v_member.id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT * INTO v_family FROM public.families WHERE id = v_member.family_id LIMIT 1;

    SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.joined_at ASC), '[]'::jsonb)
    INTO v_members
    FROM public.family_members m
    WHERE m.family_id = v_family.id AND m.status = 'ACTIVE' AND m.is_active = TRUE;

    RETURN jsonb_build_object(
        'family', to_jsonb(v_family),
        'membership', to_jsonb(v_member),
        'members', v_members
    );
END;
$$;

-- 5.10 Update Custom Family Name, Emoji, and Family Label (Admin/Owner Only)
CREATE OR REPLACE FUNCTION public.update_family_profile(
    p_family_id UUID,
    p_name TEXT,
    p_avatar_emoji VARCHAR DEFAULT '🏠',
    p_family_label TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_clean_name TEXT := trim(coalesce(p_name, ''));
    v_emoji VARCHAR(32) := coalesce(NULLIF(trim(p_avatar_emoji), ''), '🏠');
    v_label TEXT := trim(coalesce(p_family_label, ''));
    v_family public.families%ROWTYPE;
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_admin(p_family_id) THEN
        RAISE EXCEPTION 'Only family Owner or Admins can update family profile settings.';
    END IF;
    IF length(v_clean_name) = 0 OR length(v_clean_name) > 80 THEN
        RAISE EXCEPTION 'Family name must be between 1 and 80 characters.';
    END IF;

    UPDATE public.families
    SET name = v_clean_name,
        avatar_emoji = v_emoji,
        family_label = v_label,
        updated_at = NOW()
    WHERE id = p_family_id AND NOT is_deleted
    RETURNING * INTO v_family;

    IF v_family.id IS NULL THEN
        RAISE EXCEPTION 'Family not found.';
    END IF;

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'FAMILY_PROFILE_UPDATED', 'families', p_family_id,
        jsonb_build_object('name', v_clean_name, 'family_label', v_label)
    );
    RETURN to_jsonb(v_family);
END;
$$;

-- 5.11 Update Custom Member Display Name, Avatar Emoji, and Relationship/Custom Label (Self or Admin/Owner)
CREATE OR REPLACE FUNCTION public.update_family_member_profile(
    p_family_id UUID,
    p_target_user_id UUID,
    p_display_name TEXT,
    p_member_label TEXT DEFAULT '',
    p_avatar_emoji VARCHAR DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_clean_name TEXT := trim(coalesce(p_display_name, ''));
    v_clean_label TEXT := trim(coalesce(p_member_label, ''));
    v_member public.family_members%ROWTYPE;
BEGIN
    IF v_uid IS NULL OR NOT public.is_family_member(p_family_id) THEN
        RAISE EXCEPTION 'Active family membership required.';
    END IF;
    IF p_target_user_id <> v_uid AND NOT public.is_family_admin(p_family_id) THEN
        RAISE EXCEPTION 'Only the member or a family Admin/Owner can customize this profile.';
    END IF;
    IF length(v_clean_name) = 0 OR length(v_clean_name) > 60 THEN
        RAISE EXCEPTION 'Member name must be between 1 and 60 characters.';
    END IF;

    PERFORM set_config('app.family_rpc_bypass', 'on', true);

    UPDATE public.family_members
    SET display_name = v_clean_name,
        name = v_clean_name,
        member_label = v_clean_label,
        avatar_emoji = coalesce(NULLIF(trim(p_avatar_emoji), ''), avatar_emoji),
        updated_at = NOW()
    WHERE family_id = p_family_id
      AND user_id = p_target_user_id
      AND status = 'ACTIVE'
    RETURNING * INTO v_member;

    IF v_member.id IS NULL THEN
        RAISE EXCEPTION 'Active target member not found.';
    END IF;

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'MEMBER_PROFILE_UPDATED', 'family_members', v_member.id,
        jsonb_build_object('display_name', v_clean_name, 'member_label', v_clean_label)
    );
    RETURN to_jsonb(v_member);
END;
$$;

-- 5.12 Delete a Single Personal Transaction Owned by auth.uid()
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

-- 5.13 Clear All Personal Ledger Data for auth.uid() (Prevents Auto-Regain on Sync)
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

-- 5.14 Clear Family Transactions (Admin/Owner clears all; Member clears own)
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
    public.is_family_member(UUID),
    public.is_family_admin(UUID),
    public.is_family_owner(UUID),
    public.is_user_active_member_of(UUID, UUID),
    public.shares_active_family_with(UUID),
    public.log_family_audit(UUID, UUID, TEXT, TEXT, UUID, JSONB),
    public.seed_family_default_categories(UUID, UUID),
    public.create_family_atomic(TEXT, VARCHAR, TEXT, UUID, NUMERIC, TEXT, TEXT, VARCHAR),
    public.create_family_invitation(UUID, TEXT, VARCHAR, INT),
    public.accept_family_invitation(TEXT, TEXT, VARCHAR, TEXT),
    public.update_family_member_role(UUID, UUID, VARCHAR),
    public.remove_family_member(UUID, UUID),
    public.transfer_family_ownership(UUID, UUID),
    public.leave_family(UUID),
    public.close_family(UUID),
    public.get_my_active_family(),
    public.update_family_profile(UUID, TEXT, VARCHAR, TEXT),
    public.update_family_member_profile(UUID, UUID, TEXT, TEXT, VARCHAR),
    public.delete_my_personal_transaction(UUID),
    public.clear_my_personal_ledger(),
    public.clear_family_transactions_atomic(UUID)
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
    public.is_family_member(UUID),
    public.is_family_admin(UUID),
    public.is_family_owner(UUID),
    public.is_user_active_member_of(UUID, UUID),
    public.shares_active_family_with(UUID),
    public.create_family_atomic(TEXT, VARCHAR, TEXT, UUID, NUMERIC, TEXT, TEXT, VARCHAR),
    public.create_family_invitation(UUID, TEXT, VARCHAR, INT),
    public.accept_family_invitation(TEXT, TEXT, VARCHAR, TEXT),
    public.update_family_member_role(UUID, UUID, VARCHAR),
    public.remove_family_member(UUID, UUID),
    public.transfer_family_ownership(UUID, UUID),
    public.leave_family(UUID),
    public.close_family(UUID),
    public.get_my_active_family(),
    public.update_family_profile(UUID, TEXT, VARCHAR, TEXT),
    public.update_family_member_profile(UUID, UUID, TEXT, TEXT, VARCHAR),
    public.delete_my_personal_transaction(UUID),
    public.clear_my_personal_ledger(),
    public.clear_family_transactions_atomic(UUID)
TO authenticated;

-- =============================================================================
-- 6. STRICT ROW LEVEL SECURITY (RLS) ACROSS ALL FAMILY & PROFILE TABLES
-- =============================================================================

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.families ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_audit_logs ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE
    public.profiles,
    public.families,
    public.family_members,
    public.family_invitations,
    public.family_categories,
    public.family_transactions,
    public.family_budgets,
    public.family_messages,
    public.family_audit_logs
FROM anon;

-- 6.1 PROFILES (Self + Co-Members in same active family)
CREATE POLICY "profiles_select" ON public.profiles
    FOR SELECT TO authenticated
    USING (auth.uid() = id OR public.shares_active_family_with(id));
CREATE POLICY "profiles_insert" ON public.profiles
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = id);
CREATE POLICY "profiles_update" ON public.profiles
    FOR UPDATE TO authenticated
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);
CREATE POLICY "profiles_delete" ON public.profiles
    FOR DELETE TO authenticated
    USING (auth.uid() = id);

-- 6.2 FAMILIES (Read by members; update settings by admin/owner; close/delete by owner only)
CREATE POLICY "families_select" ON public.families
    FOR SELECT TO authenticated
    USING (public.is_family_member(id));
CREATE POLICY "families_insert_denied" ON public.families
    FOR INSERT TO authenticated
    WITH CHECK (FALSE);
CREATE POLICY "families_update" ON public.families
    FOR UPDATE TO authenticated
    USING (public.is_family_admin(id))
    WITH CHECK (public.is_family_admin(id));
CREATE POLICY "families_delete" ON public.families
    FOR DELETE TO authenticated
    USING (public.is_family_owner(id));

-- 6.3 FAMILY MEMBERS (Read by co-members; mutations strictly via RPC / non-owner admin guards)
CREATE POLICY "family_members_select" ON public.family_members
    FOR SELECT TO authenticated
    USING (public.is_family_member(family_id));
CREATE POLICY "family_members_insert_denied" ON public.family_members
    FOR INSERT TO authenticated
    WITH CHECK (FALSE);
CREATE POLICY "family_members_update_admin" ON public.family_members
    FOR UPDATE TO authenticated
    USING (public.is_family_admin(family_id) AND role <> 'OWNER')
    WITH CHECK (public.is_family_admin(family_id) AND role IN ('ADMIN', 'MEMBER'));
CREATE POLICY "family_members_delete_denied" ON public.family_members
    FOR DELETE TO authenticated
    USING (FALSE);

-- 6.4 FAMILY INVITATIONS (Admins/Owners inspect/revoke only; joins run via accept_family_invitation RPC)
CREATE POLICY "family_invitations_select" ON public.family_invitations
    FOR SELECT TO authenticated
    USING (public.is_family_admin(family_id));
CREATE POLICY "family_invitations_insert" ON public.family_invitations
    FOR INSERT TO authenticated
    WITH CHECK (public.is_family_admin(family_id) AND role IN ('ADMIN', 'MEMBER'));
CREATE POLICY "family_invitations_update" ON public.family_invitations
    FOR UPDATE TO authenticated
    USING (public.is_family_admin(family_id))
    WITH CHECK (public.is_family_admin(family_id));
CREATE POLICY "family_invitations_delete" ON public.family_invitations
    FOR DELETE TO authenticated
    USING (public.is_family_admin(family_id));

-- 6.5 FAMILY CATEGORIES
CREATE POLICY "family_categories_select" ON public.family_categories
    FOR SELECT TO authenticated
    USING (public.is_family_member(family_id) AND NOT is_deleted);
CREATE POLICY "family_categories_insert" ON public.family_categories
    FOR INSERT TO authenticated
    WITH CHECK (public.is_family_admin(family_id));
CREATE POLICY "family_categories_update" ON public.family_categories
    FOR UPDATE TO authenticated
    USING (public.is_family_admin(family_id))
    WITH CHECK (public.is_family_admin(family_id));
CREATE POLICY "family_categories_delete" ON public.family_categories
    FOR DELETE TO authenticated
    USING (public.is_family_admin(family_id));

-- 6.6 FAMILY TRANSACTIONS
CREATE POLICY "family_transactions_select" ON public.family_transactions
    FOR SELECT TO authenticated
    USING (public.is_family_member(family_id) AND NOT is_deleted);
CREATE POLICY "family_transactions_insert" ON public.family_transactions
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_family_member(family_id)
        AND created_by = auth.uid()
        AND (paid_by IS NULL OR public.is_user_active_member_of(paid_by, family_id))
    );
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
    );
CREATE POLICY "family_transactions_delete" ON public.family_transactions
    FOR DELETE TO authenticated
    USING (
        public.is_family_member(family_id)
        AND (created_by = auth.uid() OR public.is_family_admin(family_id))
    );

-- 6.7 FAMILY BUDGETS (Shared across family; Admin/Owner manages)
CREATE POLICY "family_budgets_select" ON public.family_budgets
    FOR SELECT TO authenticated
    USING (public.is_family_member(family_id) AND NOT is_deleted);
CREATE POLICY "family_budgets_insert" ON public.family_budgets
    FOR INSERT TO authenticated
    WITH CHECK (public.is_family_admin(family_id));
CREATE POLICY "family_budgets_update" ON public.family_budgets
    FOR UPDATE TO authenticated
    USING (public.is_family_admin(family_id))
    WITH CHECK (public.is_family_admin(family_id));
CREATE POLICY "family_budgets_delete" ON public.family_budgets
    FOR DELETE TO authenticated
    USING (public.is_family_admin(family_id));

-- 6.8 FAMILY MESSAGES (WhatsApp-style group chat; strictly current family members)
CREATE POLICY "family_messages_select" ON public.family_messages
    FOR SELECT TO authenticated
    USING (public.is_family_member(family_id) AND NOT is_deleted);
CREATE POLICY "family_messages_insert" ON public.family_messages
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_family_member(family_id)
        AND sender_user_id = auth.uid()
    );
CREATE POLICY "family_messages_update" ON public.family_messages
    FOR UPDATE TO authenticated
    USING (
        public.is_family_member(family_id)
        AND (sender_user_id = auth.uid() OR public.is_family_admin(family_id))
    )
    WITH CHECK (
        public.is_family_member(family_id)
        AND (sender_user_id = auth.uid() OR public.is_family_admin(family_id))
    );
CREATE POLICY "family_messages_delete" ON public.family_messages
    FOR DELETE TO authenticated
    USING (
        public.is_family_member(family_id)
        AND (sender_user_id = auth.uid() OR public.is_family_admin(family_id))
    );

-- 6.9 FAMILY AUDIT LOGS (Admin/Owner read-only; inserts restricted to SECURITY DEFINER helpers)
CREATE POLICY "family_audit_logs_select" ON public.family_audit_logs
    FOR SELECT TO authenticated
    USING (public.is_family_admin(family_id));
CREATE POLICY "family_audit_logs_insert_denied" ON public.family_audit_logs
    FOR INSERT TO authenticated
    WITH CHECK (FALSE);

-- =============================================================================
-- 7. SUPABASE REALTIME PUBLICATION & PRIVATE BROADCAST CHANNEL RLS
-- =============================================================================

ALTER TABLE public.families REPLICA IDENTITY FULL;
ALTER TABLE public.family_members REPLICA IDENTITY FULL;
ALTER TABLE public.family_transactions REPLICA IDENTITY FULL;
ALTER TABLE public.family_categories REPLICA IDENTITY FULL;
ALTER TABLE public.family_budgets REPLICA IDENTITY FULL;
ALTER TABLE public.family_messages REPLICA IDENTITY FULL;

DO $$
DECLARE
    t text;
    target_tables text[] := ARRAY[
        'families', 'family_members', 'family_categories',
        'family_transactions', 'family_budgets', 'family_messages'
    ];
BEGIN
    FOREACH t IN ARRAY target_tables
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_publication_tables
            WHERE pubname = 'supabase_realtime'
              AND schemaname = 'public'
              AND tablename = t
        ) THEN
            BEGIN
                EXECUTE format('ALTER PUBLICATION supabase_realtime ADD TABLE public.%I', t);
            EXCEPTION WHEN OTHERS THEN
                NULL;
            END;
        END IF;
    END LOOP;
END $$;

-- Private Realtime Broadcast Authorization (family:<family_uuid>)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'realtime' AND table_name = 'messages'
    ) THEN
        BEGIN
            EXECUTE 'DROP POLICY IF EXISTS "family_broadcast_read" ON realtime.messages';
            EXECUTE 'DROP POLICY IF EXISTS "family_broadcast_write" ON realtime.messages';

            EXECUTE $p$
                CREATE POLICY "family_broadcast_read" ON realtime.messages
                FOR SELECT TO authenticated
                USING (
                    realtime.topic() LIKE 'family:%'
                    AND split_part(realtime.topic(), ':', 2) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    AND public.is_family_member(split_part(realtime.topic(), ':', 2)::uuid)
                )
            $p$;

            EXECUTE $p$
                CREATE POLICY "family_broadcast_write" ON realtime.messages
                FOR INSERT TO authenticated
                WITH CHECK (
                    realtime.topic() LIKE 'family:%'
                    AND split_part(realtime.topic(), ':', 2) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    AND public.is_family_member(split_part(realtime.topic(), ':', 2)::uuid)
                )
            $p$;
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;
    END IF;
END $$;
