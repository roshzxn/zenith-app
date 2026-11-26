-- =============================================================================
-- ZENITH FINANCE: MASTER SUPABASE POSTGRESQL SCHEMA & REALTIME BACKEND
-- Description: Complete, self-healing, non-recursive PostgreSQL schema, RLS policies,
--              indexes, realtime configuration, and atomic stored procedures.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- 1. PURGE OLD / BROKEN / RECURSIVE POLICIES (Fixes Error 42P17)
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
            'profiles', 'families', 'family_members', 'categories',
            'transactions', 'budgets', 'savings_goals',
            'savings_contributions', 'audit_logs', 'family_invitations',
            'auto_pay_reminders', 'family_categories', 'family_transactions',
            'family_budgets', 'family_messages', 'family_audit_logs'
          )
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I.%I', pol.policyname, pol.schemaname, pol.tablename);
    END LOOP;
END $$;

-- =============================================================================
-- 2. TABLE CREATIONS & DEFENSIVE COLUMN ADDITIONS
-- =============================================================================

-- 2.1 PROFILES TABLE
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    full_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS email TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS full_name TEXT NOT NULL DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 2.2 FAMILIES TABLE
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

ALTER TABLE public.families ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Family Vault';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS avatar_emoji VARCHAR(32) NOT NULL DEFAULT '🏠';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS family_label TEXT NOT NULL DEFAULT '';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES public.profiles(id) ON DELETE RESTRICT;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS monthly_budget_limit NUMERIC(15, 2) NOT NULL DEFAULT 0.00;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS idempotency_key UUID;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

DROP TRIGGER IF EXISTS trg_guard_families_update ON public.families;
UPDATE public.families SET owner_id = created_by WHERE owner_id IS NULL AND created_by IS NOT NULL;
UPDATE public.families SET avatar_emoji = '🏠' WHERE avatar_emoji IS NULL OR length(trim(avatar_emoji)) = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_families_owner_idempotency
    ON public.families (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 2.3 FAMILY MEMBERS TABLE (Enforces 1 active family per authenticated user)
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

ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS display_name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS member_label TEXT NOT NULL DEFAULT '';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS avatar_emoji VARCHAR(32) NOT NULL DEFAULT '🙂';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS avatar_color TEXT DEFAULT '#10B981';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

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
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_family_members_family_user ON public.family_members (family_id, user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_family_members_active_user ON public.family_members (user_id) WHERE status = 'ACTIVE' AND is_active = TRUE;

-- 2.4 CATEGORIES TABLE
CREATE TABLE IF NOT EXISTS public.categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    icon TEXT DEFAULT 'Category',
    icon_name TEXT DEFAULT 'Category',
    color TEXT DEFAULT '#10B981',
    color_hex TEXT DEFAULT '#10B981',
    type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS name TEXT;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS icon TEXT DEFAULT 'Category';
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS icon_name TEXT DEFAULT 'Category';
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS color TEXT DEFAULT '#10B981';
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS color_hex TEXT DEFAULT '#10B981';
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS type VARCHAR(32) DEFAULT 'EXPENSE';
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE public.categories ALTER COLUMN color SET DEFAULT '#10B981';
ALTER TABLE public.categories ALTER COLUMN icon SET DEFAULT 'Category';
DO $$ BEGIN ALTER TABLE public.categories ALTER COLUMN color DROP NOT NULL; EXCEPTION WHEN others THEN null; END $$;

-- Seed default categories if they do not already exist (Idempotent)
INSERT INTO public.categories (name, icon, icon_name, color, color_hex, type, is_default)
SELECT val.name, val.icon, val.icon_name, val.color, val.color_hex, val.type, val.is_default
FROM (
    VALUES
        ('Food & Dining', 'Restaurant', 'Restaurant', '#F59E0B', '#F59E0B', 'EXPENSE', TRUE),
        ('Groceries', 'ShoppingCart', 'ShoppingCart', '#10B981', '#10B981', 'EXPENSE', TRUE),
        ('Transportation', 'DirectionsCar', 'DirectionsCar', '#3B82F6', '#3B82F6', 'EXPENSE', TRUE),
        ('Shopping', 'ShoppingBag', 'ShoppingBag', '#EC4899', '#EC4899', 'EXPENSE', TRUE),
        ('Bills & Utilities', 'Receipt', 'Receipt', '#EF4444', '#EF4444', 'EXPENSE', TRUE),
        ('Entertainment', 'Movie', 'Movie', '#8B5CF6', '#8B5CF6', 'EXPENSE', TRUE),
        ('Health & Fitness', 'FitnessCenter', 'FitnessCenter', '#14B8A6', '#14B8A6', 'EXPENSE', TRUE),
        ('Education', 'School', 'School', '#6366F1', '#6366F1', 'EXPENSE', TRUE),
        ('Travel', 'Flight', 'Flight', '#06B6D4', '#06B6D4', 'EXPENSE', TRUE),
        ('Salary', 'AttachMoney', 'AttachMoney', '#10B981', '#10B981', 'INCOME', TRUE),
        ('Investment', 'TrendingUp', 'TrendingUp', '#059669', '#059669', 'INCOME', TRUE),
        ('Freelance', 'Work', 'Work', '#3B82F6', '#3B82F6', 'INCOME', TRUE),
        ('Other', 'Category', 'Category', '#64748B', '#64748B', 'EXPENSE', TRUE)
) AS val(name, icon, icon_name, color, color_hex, type, is_default)
WHERE NOT EXISTS (
    SELECT 1 FROM public.categories c
    WHERE c.name = val.name AND c.family_id IS NULL AND c.user_id IS NULL
);

-- 2.5 TRANSACTIONS TABLE
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    family_id UUID REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    paid_by_member_id UUID REFERENCES public.family_members(id) ON DELETE SET NULL,
    paid_by_name TEXT DEFAULT 'Member',
    title TEXT DEFAULT 'Transaction',
    description TEXT NOT NULL DEFAULT '',
    amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE',
    transaction_type VARCHAR(32) DEFAULT 'EXPENSE',
    category TEXT NOT NULL DEFAULT 'Other',
    category_id TEXT DEFAULT 'Other',
    payment_method TEXT NOT NULL DEFAULT 'UPI',
    upi_id TEXT,
    upi_transaction_id TEXT,
    transaction_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version BIGINT NOT NULL DEFAULT 1,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS paid_by_member_id UUID REFERENCES public.family_members(id) ON DELETE SET NULL;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS paid_by_name TEXT DEFAULT 'Member';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS title TEXT DEFAULT 'Transaction';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS amount NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS type VARCHAR(32) DEFAULT 'EXPENSE';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(32) DEFAULT 'EXPENSE';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS category TEXT DEFAULT 'Other';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS payment_method TEXT DEFAULT 'UPI';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS upi_id TEXT;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS upi_transaction_id TEXT;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS transaction_date TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS sync_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE public.transactions ALTER COLUMN category SET DEFAULT 'Other';
ALTER TABLE public.transactions ALTER COLUMN title SET DEFAULT 'Transaction';
ALTER TABLE public.transactions ALTER COLUMN description SET DEFAULT '';

-- 2.6 BUDGETS TABLE
CREATE TABLE IF NOT EXISTS public.budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    family_id UUID REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    name TEXT NOT NULL DEFAULT 'Budget',
    category_name TEXT DEFAULT 'Other',
    category_id TEXT DEFAULT 'Other',
    monthly_limit NUMERIC(15, 2) DEFAULT 0.00,
    amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    month_year VARCHAR(7) DEFAULT to_char(NOW(), 'YYYY-MM'),
    period_type VARCHAR(32) DEFAULT 'MONTHLY',
    start_date TIMESTAMPTZ,
    end_date TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Budget';
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS category_name TEXT DEFAULT 'Other';
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS monthly_limit NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS amount NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS month_year VARCHAR(7) DEFAULT to_char(NOW(), 'YYYY-MM');
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS period_type VARCHAR(32) DEFAULT 'MONTHLY';
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS start_date TIMESTAMPTZ;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS end_date TIMESTAMPTZ;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.budgets ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE public.budgets ALTER COLUMN category_name SET DEFAULT 'Other';

-- Defensive conversion of category_id across transactions & budgets (Fixes Error 22P02 on legacy instances)
DO $$
DECLARE
    r RECORD;
BEGIN
    -- 1. Drop foreign key constraints on category_id in transactions and budgets
    FOR r IN (
        SELECT tc.table_schema, tc.table_name, tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
          AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
          AND tc.table_name IN ('transactions', 'budgets')
          AND kcu.column_name = 'category_id'
    ) LOOP
        EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT IF EXISTS %I CASCADE', r.table_schema, r.table_name, r.constraint_name);
    END LOOP;

    -- Also drop from pg_constraint safely without casting to regclass
    FOR r IN (
        SELECT con.conname, n.nspname || '.' || c.relname as tablename
        FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relname IN ('transactions', 'budgets')
          AND con.contype = 'f'
          AND con.conname ILIKE '%category%'
    ) LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT IF EXISTS %I CASCADE', r.tablename, r.conname);
    END LOOP;
    
    -- 2. Drop any legacy trigger on transactions
    DROP TRIGGER IF EXISTS trg_sync_transaction_columns ON public.transactions;
    
    -- 3. Safely handle transactions.category_id conversion
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'transactions' AND column_name = 'category_id'
    ) THEN
        BEGIN
            ALTER TABLE public.transactions ALTER COLUMN category_id DROP DEFAULT;
            ALTER TABLE public.transactions ALTER COLUMN category_id DROP NOT NULL;
            ALTER TABLE public.transactions ALTER COLUMN category_id TYPE TEXT USING category_id::text;
            ALTER TABLE public.transactions ALTER COLUMN category_id SET DEFAULT 'Other';
        EXCEPTION WHEN OTHERS THEN
            ALTER TABLE public.transactions DROP COLUMN IF EXISTS category_id CASCADE;
            ALTER TABLE public.transactions ADD COLUMN category_id TEXT DEFAULT 'Other';
        END;
    ELSE
        ALTER TABLE public.transactions ADD COLUMN category_id TEXT DEFAULT 'Other';
    END IF;

    -- 4. Safely handle budgets.category_id conversion
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'budgets' AND column_name = 'category_id'
    ) THEN
        BEGIN
            ALTER TABLE public.budgets ALTER COLUMN category_id DROP DEFAULT;
            ALTER TABLE public.budgets ALTER COLUMN category_id DROP NOT NULL;
            ALTER TABLE public.budgets ALTER COLUMN category_id TYPE TEXT USING category_id::text;
            ALTER TABLE public.budgets ALTER COLUMN category_id SET DEFAULT 'Other';
        EXCEPTION WHEN OTHERS THEN
            ALTER TABLE public.budgets DROP COLUMN IF EXISTS category_id CASCADE;
            ALTER TABLE public.budgets ADD COLUMN category_id TEXT DEFAULT 'Other';
        END;
    ELSE
        ALTER TABLE public.budgets ADD COLUMN category_id TEXT DEFAULT 'Other';
    END IF;
END $$;

-- 2.7 SAVINGS GOALS & CONTRIBUTIONS
CREATE TABLE IF NOT EXISTS public.savings_goals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    family_id UUID REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    name TEXT NOT NULL DEFAULT 'Goal',
    title TEXT DEFAULT 'Goal',
    target_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    current_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    target_date TIMESTAMPTZ,
    icon_name TEXT NOT NULL DEFAULT 'Savings',
    color_hex TEXT NOT NULL DEFAULT '#059669',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Goal';
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS title TEXT DEFAULT 'Goal';
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS target_amount NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS current_amount NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS target_date TIMESTAMPTZ;
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS icon_name TEXT DEFAULT 'Savings';
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS color_hex TEXT DEFAULT '#059669';
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.savings_goals ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE TABLE IF NOT EXISTS public.savings_contributions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id UUID NOT NULL REFERENCES public.savings_goals(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    member_id UUID REFERENCES public.family_members(id) ON DELETE SET NULL,
    amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    note TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.savings_contributions ADD COLUMN IF NOT EXISTS goal_id UUID REFERENCES public.savings_goals(id) ON DELETE CASCADE;
ALTER TABLE public.savings_contributions ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.savings_contributions ADD COLUMN IF NOT EXISTS member_id UUID REFERENCES public.family_members(id) ON DELETE SET NULL;
ALTER TABLE public.savings_contributions ADD COLUMN IF NOT EXISTS amount NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.savings_contributions ADD COLUMN IF NOT EXISTS note TEXT DEFAULT '';
ALTER TABLE public.savings_contributions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 2.8 LEGACY AUDIT LOGS (Locked) & DEDICATED FAMILY TABLES
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID REFERENCES public.families(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    entity_name TEXT NOT NULL,
    entity_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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

-- 2.9 FAMILY INVITATIONS
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
    ALTER TABLE public.family_invitations ADD CONSTRAINT chk_family_inv_role CHECK (role IN ('ADMIN', 'MEMBER'));
    ALTER TABLE public.family_invitations DROP CONSTRAINT IF EXISTS chk_family_inv_status;
    ALTER TABLE public.family_invitations ADD CONSTRAINT chk_family_inv_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'));
EXCEPTION WHEN OTHERS THEN NULL;
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

-- 2.11 FAMILY CATEGORIES, TRANSACTIONS, BUDGETS & GROUP CHAT
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

-- 2.10 AUTO PAY REMINDERS TABLE
CREATE TABLE IF NOT EXISTS public.auto_pay_reminders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    family_id UUID REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    title TEXT NOT NULL DEFAULT '',
    amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    category TEXT NOT NULL DEFAULT 'Bills & Utilities',
    payment_method TEXT NOT NULL DEFAULT 'UPI',
    frequency VARCHAR(32) NOT NULL DEFAULT 'MONTHLY',
    due_day_of_month INT NOT NULL DEFAULT 1,
    next_due_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_auto_debit BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_days_before INT NOT NULL DEFAULT 2,
    is_paused BOOLEAN NOT NULL DEFAULT FALSE,
    last_paid_date TIMESTAMPTZ,
    notes TEXT NOT NULL DEFAULT '',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS title TEXT DEFAULT '';
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS amount NUMERIC(15, 2) DEFAULT 0.00;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS category TEXT DEFAULT 'Bills & Utilities';
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS payment_method TEXT DEFAULT 'UPI';
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS frequency VARCHAR(32) DEFAULT 'MONTHLY';
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS due_day_of_month INT DEFAULT 1;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS next_due_date TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS is_auto_debit BOOLEAN DEFAULT TRUE;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS reminder_days_before INT DEFAULT 2;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS is_paused BOOLEAN DEFAULT FALSE;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS last_paid_date TIMESTAMPTZ;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS notes TEXT DEFAULT '';
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.auto_pay_reminders ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- =============================================================================
-- 3. BIDIRECTIONAL COLUMN SYNCHRONIZATION TRIGGERS
-- =============================================================================

CREATE OR REPLACE FUNCTION public.sync_transaction_columns()
RETURNS TRIGGER AS $$
BEGIN
    -- 1. Sync transaction_type and type
    IF NEW.transaction_type IS NOT NULL AND trim(NEW.transaction_type) <> '' THEN
        NEW.type := NEW.transaction_type;
    ELSIF NEW.type IS NOT NULL AND trim(NEW.type) <> '' THEN
        NEW.transaction_type := NEW.type;
    ELSE
        NEW.type := 'EXPENSE';
        NEW.transaction_type := 'EXPENSE';
    END IF;

    -- 2. Safely sync category and category_id without invalid UUID casts
    IF NEW.category IS NOT NULL AND trim(NEW.category) <> '' THEN
        NEW.category_id := NEW.category;
    ELSIF NEW.category_id IS NOT NULL AND trim(NEW.category_id::text) <> '' THEN
        NEW.category := NEW.category_id::text;
    ELSE
        NEW.category := 'Other';
        NEW.category_id := 'Other';
    END IF;

    -- 3. Ensure title and description are properly initialized
    IF NEW.title IS NULL OR trim(NEW.title) = '' THEN
        NEW.title := COALESCE(NULLIF(trim(NEW.description), ''), 'Transaction');
    END IF;
    IF NEW.description IS NULL THEN
        NEW.description := COALESCE(NEW.title, '');
    END IF;

    -- 4. Default sync_version and dates
    IF NEW.sync_version IS NULL THEN
        NEW.sync_version := 1;
    END IF;
    IF NEW.transaction_date IS NULL THEN
        NEW.transaction_date := NOW();
    END IF;
    IF NEW.created_at IS NULL THEN
        NEW.created_at := NOW();
    END IF;
    NEW.updated_at := NOW();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sync_transaction_columns ON public.transactions;
CREATE TRIGGER trg_sync_transaction_columns
    BEFORE INSERT OR UPDATE ON public.transactions
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_transaction_columns();

DROP TRIGGER IF EXISTS trg_sync_family_member_columns ON public.family_members;

-- =============================================================================
-- 4. AUTH TRIGGERS: INSTANT EMAIL CONFIRMATION & PROFILE PROVISIONING
-- =============================================================================

-- 4.1 Automatically confirms emails on signup to bypass email server blocking
DO $$
BEGIN
    CREATE OR REPLACE FUNCTION public.auto_confirm_new_user()
    RETURNS TRIGGER AS $func$
    BEGIN
        NEW.email_confirmed_at := COALESCE(NEW.email_confirmed_at, NOW());
        -- Note: confirmed_at is a generated column in Supabase and computed automatically
        RETURN NEW;
    END;
    $func$ LANGUAGE plpgsql SECURITY DEFINER;

    DROP TRIGGER IF EXISTS trg_auto_confirm_user ON auth.users;
    CREATE TRIGGER trg_auto_confirm_user
        BEFORE INSERT ON auth.users
        FOR EACH ROW
        EXECUTE FUNCTION public.auto_confirm_new_user();

    -- Ensure any existing unconfirmed users are marked confirmed without touching generated columns
    UPDATE auth.users 
    SET email_confirmed_at = COALESCE(email_confirmed_at, NOW())
    WHERE email_confirmed_at IS NULL;
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

-- 4.2 Automatically creates profile entry upon new user signup
DO $$
BEGIN
    CREATE OR REPLACE FUNCTION public.handle_new_user()
    RETURNS TRIGGER AS $func$
    BEGIN
        INSERT INTO public.profiles (id, email, full_name)
        VALUES (
            NEW.id,
            COALESCE(NEW.email, ''),
            COALESCE(NEW.raw_user_meta_data->>'full_name', split_part(COALESCE(NEW.email, 'User'), '@', 1))
        )
        ON CONFLICT (id) DO UPDATE
        SET email = EXCLUDED.email,
            full_name = CASE WHEN public.profiles.full_name = '' THEN EXCLUDED.full_name ELSE public.profiles.full_name END,
            updated_at = NOW();
        RETURN NEW;
    END;
    $func$ LANGUAGE plpgsql SECURITY DEFINER;

    DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
    CREATE TRIGGER on_auth_user_created
        AFTER INSERT ON auth.users
        FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

    -- Backfill profiles for existing auth users missing a profile record
    INSERT INTO public.profiles (id, email, full_name)
    SELECT 
        u.id, 
        COALESCE(u.email, ''), 
        COALESCE(u.raw_user_meta_data->>'full_name', split_part(COALESCE(u.email, 'User'), '@', 1))
    FROM auth.users u
    WHERE NOT EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = u.id)
    ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

-- =============================================================================
-- 5. PERFORMANCE & LOOKUP INDEXES
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_transactions_user_date ON public.transactions (user_id, transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_auto_pay_user ON public.auto_pay_reminders (user_id, next_due_date ASC);
CREATE INDEX IF NOT EXISTS idx_family_members_family_active ON public.family_members (family_id, role) WHERE status = 'ACTIVE' AND is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_family_invitations_family_status ON public.family_invitations (family_id, status);
CREATE INDEX IF NOT EXISTS idx_family_transactions_timeline ON public.family_transactions (family_id, transaction_date DESC) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_family_transactions_paid_by ON public.family_transactions (family_id, paid_by) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_family_transactions_created_by ON public.family_transactions (family_id, created_by);
CREATE INDEX IF NOT EXISTS idx_family_messages_timeline ON public.family_messages (family_id, created_at DESC) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_family_audit_logs_family_date ON public.family_audit_logs (family_id, created_at DESC);

-- =============================================================================
-- 6. NON-RECURSIVE SECURITY DEFINER HELPERS & FAMILY TRIGGERS
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
-- 7. STRICT ROW LEVEL SECURITY (RLS) POLICIES (PERSONAL + SHARED FAMILY)
-- =============================================================================

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.families ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.savings_goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.savings_contributions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auto_pay_reminders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.family_audit_logs ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE
    public.profiles,
    public.families,
    public.family_members,
    public.categories,
    public.transactions,
    public.budgets,
    public.savings_goals,
    public.savings_contributions,
    public.audit_logs,
    public.family_invitations,
    public.auto_pay_reminders,
    public.family_categories,
    public.family_transactions,
    public.family_budgets,
    public.family_messages,
    public.family_audit_logs
FROM anon;

-- 7.1 Profiles: own profile + co-members inside the same active family
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

-- 7.2 Retired legacy stub tables locked
CREATE POLICY "audit_logs_locked" ON public.audit_logs
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);
CREATE POLICY "savings_contributions_locked" ON public.savings_contributions
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);

-- 7.3 Shared Family RLS Policies
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
    USING (public.is_family_member(family_id) AND (created_by = auth.uid() OR public.is_family_admin(family_id)))
    WITH CHECK (
        public.is_family_member(family_id)
        AND (created_by = auth.uid() OR public.is_family_admin(family_id))
        AND (is_deleted = TRUE OR paid_by IS NULL OR public.is_user_active_member_of(paid_by, family_id))
    );
CREATE POLICY "family_transactions_delete" ON public.family_transactions
    FOR DELETE TO authenticated
    USING (public.is_family_member(family_id) AND (created_by = auth.uid() OR public.is_family_admin(family_id)));

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

CREATE POLICY "family_messages_select" ON public.family_messages
    FOR SELECT TO authenticated
    USING (public.is_family_member(family_id) AND NOT is_deleted);
CREATE POLICY "family_messages_insert" ON public.family_messages
    FOR INSERT TO authenticated
    WITH CHECK (public.is_family_member(family_id) AND sender_user_id = auth.uid());
CREATE POLICY "family_messages_update" ON public.family_messages
    FOR UPDATE TO authenticated
    USING (public.is_family_member(family_id) AND (sender_user_id = auth.uid() OR public.is_family_admin(family_id)))
    WITH CHECK (public.is_family_member(family_id) AND (sender_user_id = auth.uid() OR public.is_family_admin(family_id)));
CREATE POLICY "family_messages_delete" ON public.family_messages
    FOR DELETE TO authenticated
    USING (public.is_family_member(family_id) AND (sender_user_id = auth.uid() OR public.is_family_admin(family_id)));

CREATE POLICY "family_audit_logs_select" ON public.family_audit_logs
    FOR SELECT TO authenticated
    USING (public.is_family_admin(family_id));
CREATE POLICY "family_audit_logs_insert_denied" ON public.family_audit_logs
    FOR INSERT TO authenticated
    WITH CHECK (FALSE);

-- 7.4 Personal Categories / Transactions / Budgets / Goals / Reminders (Strict Owner Only)
CREATE POLICY "categories_select" ON public.categories
    FOR SELECT TO authenticated
    USING (is_default = TRUE OR user_id = auth.uid());
CREATE POLICY "categories_insert" ON public.categories
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid() AND is_default = FALSE);
CREATE POLICY "categories_update" ON public.categories
    FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "categories_delete" ON public.categories
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

CREATE POLICY "transactions_select" ON public.transactions
    FOR SELECT TO authenticated
    USING (user_id = auth.uid() AND NOT is_deleted);
CREATE POLICY "transactions_insert" ON public.transactions
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "transactions_update" ON public.transactions
    FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "transactions_delete" ON public.transactions
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

CREATE POLICY "budgets_select" ON public.budgets
    FOR SELECT TO authenticated
    USING (user_id = auth.uid() AND NOT is_deleted);
CREATE POLICY "budgets_insert" ON public.budgets
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "budgets_update" ON public.budgets
    FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "budgets_delete" ON public.budgets
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

CREATE POLICY "savings_goals_select" ON public.savings_goals
    FOR SELECT TO authenticated
    USING (user_id = auth.uid() AND NOT is_deleted);
CREATE POLICY "savings_goals_insert" ON public.savings_goals
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "savings_goals_update" ON public.savings_goals
    FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "savings_goals_delete" ON public.savings_goals
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

CREATE POLICY "auto_pay_reminders_select" ON public.auto_pay_reminders
    FOR SELECT TO authenticated
    USING (user_id = auth.uid() AND NOT is_deleted);
CREATE POLICY "auto_pay_reminders_insert" ON public.auto_pay_reminders
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "auto_pay_reminders_update" ON public.auto_pay_reminders
    FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
CREATE POLICY "auto_pay_reminders_delete" ON public.auto_pay_reminders
    FOR DELETE TO authenticated
    USING (user_id = auth.uid());

-- =============================================================================
-- 8. REALTIME SUBSCRIPTION & PRIVATE BROADCAST CHANNEL CONFIGURATION
-- =============================================================================
ALTER TABLE public.transactions REPLICA IDENTITY FULL;
ALTER TABLE public.budgets REPLICA IDENTITY FULL;
ALTER TABLE public.savings_goals REPLICA IDENTITY FULL;
ALTER TABLE public.categories REPLICA IDENTITY FULL;
ALTER TABLE public.profiles REPLICA IDENTITY FULL;
ALTER TABLE public.auto_pay_reminders REPLICA IDENTITY FULL;
ALTER TABLE public.families REPLICA IDENTITY FULL;
ALTER TABLE public.family_members REPLICA IDENTITY FULL;
ALTER TABLE public.family_categories REPLICA IDENTITY FULL;
ALTER TABLE public.family_transactions REPLICA IDENTITY FULL;
ALTER TABLE public.family_budgets REPLICA IDENTITY FULL;
ALTER TABLE public.family_messages REPLICA IDENTITY FULL;

DO $$
DECLARE
    t text;
    target_tables text[] := ARRAY[
        'profiles', 'categories', 'transactions', 'budgets', 'savings_goals', 'auto_pay_reminders',
        'families', 'family_members', 'family_categories', 'family_transactions', 'family_budgets', 'family_messages'
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
            EXCEPTION WHEN OTHERS THEN NULL;
            END;
        END IF;
    END LOOP;
END $$;

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
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
    END IF;
END $$;

-- =============================================================================
-- 9. ATOMIC FAMILY WORKFLOW RPCs
-- =============================================================================
DROP FUNCTION IF EXISTS public.create_family(TEXT);
DROP FUNCTION IF EXISTS public.join_family_by_code(VARCHAR);
DROP FUNCTION IF EXISTS public.get_family_dashboard(UUID);

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

    PERFORM pg_advisory_xact_lock(hashtext('user_family_' || v_uid::text));

    IF p_idempotency_key IS NOT NULL THEN
        SELECT * INTO v_existing_family
        FROM public.families
        WHERE owner_id = v_uid AND idempotency_key = p_idempotency_key AND NOT is_deleted
        LIMIT 1;
        IF v_existing_family.id IS NOT NULL THEN
            SELECT * INTO v_member FROM public.family_members
            WHERE family_id = v_existing_family.id AND user_id = v_uid LIMIT 1;
            RETURN jsonb_build_object('family', to_jsonb(v_existing_family), 'membership', to_jsonb(v_member));
        END IF;
    END IF;

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

    INSERT INTO public.families (name, avatar_emoji, family_label, owner_id, created_by, monthly_budget_limit, idempotency_key)
    VALUES (v_clean_name, v_emoji, v_clean_label, v_uid, v_uid, coalesce(p_monthly_budget_limit, 0.00), p_idempotency_key)
    RETURNING * INTO v_family;

    INSERT INTO public.family_members (family_id, user_id, name, display_name, member_label, avatar_emoji, role, status, is_active)
    VALUES (v_family.id, v_uid, v_display_name, v_display_name, v_creator_label, v_creator_emoji, 'OWNER', 'ACTIVE', TRUE)
    RETURNING * INTO v_member;

    PERFORM public.seed_family_default_categories(v_family.id, v_uid);

    INSERT INTO public.family_messages (family_id, sender_user_id, sender_name, sender_avatar, message_type, body)
    VALUES (v_family.id, v_uid, v_display_name, v_emoji, 'SYSTEM', format('%s created the %s family group.', v_display_name, v_clean_name));

    PERFORM public.log_family_audit(v_family.id, v_uid, 'FAMILY_CREATED', 'families', v_family.id, jsonb_build_object('name', v_family.name, 'avatar_emoji', v_family.avatar_emoji));

    RETURN jsonb_build_object('family', to_jsonb(v_family), 'membership', to_jsonb(v_member));
END;
$$;

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
    IF v_uid IS NULL OR NOT public.is_family_admin(p_family_id) THEN
        RAISE EXCEPTION 'Only family owners or admins can create invitations.';
    END IF;
    IF v_clean_role NOT IN ('ADMIN', 'MEMBER') THEN
        RAISE EXCEPTION 'Invitation role must be ADMIN or MEMBER.';
    END IF;

    UPDATE public.family_invitations SET status = 'EXPIRED', updated_at = NOW()
    WHERE family_id = p_family_id AND status = 'PENDING' AND expires_at <= NOW();

    v_code := 'FAM-' || upper(substring(replace(gen_random_uuid()::text, '-', ''), 1, 6));
    INSERT INTO public.family_invitations (family_id, invited_email, email, invite_code, role, status, invited_by, expires_at)
    VALUES (
        p_family_id, lower(trim(coalesce(p_invited_email, ''))), lower(trim(coalesce(p_invited_email, ''))),
        v_code, v_clean_role, 'PENDING', v_uid,
        NOW() + make_interval(hours => GREATEST(1, LEAST(coalesce(p_expires_hours, 168), 720)))
    )
    RETURNING * INTO v_inv;

    PERFORM public.log_family_audit(p_family_id, v_uid, 'INVITATION_CREATED', 'family_invitations', v_inv.id, jsonb_build_object('role', v_clean_role));
    RETURN to_jsonb(v_inv);
END;
$$;

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

    SELECT * INTO v_inv FROM public.family_invitations
    WHERE status = 'PENDING' AND (upper(invite_code) = upper(v_input) OR invite_token::text = v_input)
    LIMIT 1 FOR UPDATE;

    IF v_inv.id IS NULL THEN
        RAISE EXCEPTION 'Invitation is invalid or has already been used.';
    END IF;
    IF v_inv.expires_at <= NOW() THEN
        UPDATE public.family_invitations SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_inv.id;
        RAISE EXCEPTION 'Invitation has expired.';
    END IF;

    SELECT * INTO v_family FROM public.families WHERE id = v_inv.family_id AND NOT is_deleted LIMIT 1;
    IF v_family.id IS NULL THEN
        RAISE EXCEPTION 'Target family is no longer active.';
    END IF;

    SELECT email, full_name INTO v_profile FROM public.profiles WHERE id = v_uid LIMIT 1;
    IF length(trim(coalesce(v_inv.invited_email, ''))) > 0 AND lower(trim(coalesce(v_profile.email, ''))) <> lower(trim(v_inv.invited_email)) THEN
        RAISE EXCEPTION 'This invitation is restricted to another email address.';
    END IF;

    SELECT fm.family_id INTO v_existing_active FROM public.family_members fm
    WHERE fm.user_id = v_uid AND fm.status = 'ACTIVE' AND fm.is_active = TRUE LIMIT 1;
    IF v_existing_active.family_id IS NOT NULL THEN
        RAISE EXCEPTION 'You already belong to a family. Leave your current family before joining another.';
    END IF;

    v_name := coalesce(
        NULLIF(trim(p_display_name), ''),
        NULLIF(trim(v_profile.full_name), ''),
        NULLIF(split_part(coalesce(v_profile.email, ''), '@', 1), ''),
        'Member'
    );

    INSERT INTO public.family_members (family_id, user_id, name, display_name, member_label, avatar_emoji, role, status, is_active, joined_at)
    VALUES (v_family.id, v_uid, v_name, v_name, v_label, v_emoji, v_inv.role, 'ACTIVE', TRUE, NOW())
    ON CONFLICT (family_id, user_id) DO UPDATE
    SET status = 'ACTIVE', is_active = TRUE,
        role = CASE WHEN public.family_members.role = 'OWNER' THEN 'OWNER' ELSE EXCLUDED.role END,
        display_name = EXCLUDED.display_name, name = EXCLUDED.name, member_label = EXCLUDED.member_label, avatar_emoji = EXCLUDED.avatar_emoji,
        joined_at = NOW(), updated_at = NOW()
    RETURNING * INTO v_member;

    UPDATE public.family_invitations SET status = 'ACCEPTED', accepted_by = v_uid, accepted_at = NOW(), updated_at = NOW() WHERE id = v_inv.id;

    INSERT INTO public.family_messages (family_id, sender_user_id, sender_name, sender_avatar, message_type, body)
    VALUES (v_family.id, v_uid, v_name, v_emoji, 'SYSTEM', format('%s joined the family.', v_name));

    PERFORM public.log_family_audit(v_family.id, v_uid, 'INVITATION_ACCEPTED', 'family_members', v_member.id, jsonb_build_object('invitation_id', v_inv.id, 'role', v_member.role));
    RETURN jsonb_build_object('family', to_jsonb(v_family), 'membership', to_jsonb(v_member));
END;
$$;

CREATE OR REPLACE FUNCTION public.update_family_member_role(p_family_id UUID, p_target_user_id UUID, p_new_role VARCHAR)
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
    SELECT * INTO v_target FROM public.family_members
    WHERE family_id = p_family_id AND user_id = p_target_user_id AND status = 'ACTIVE' AND is_active = TRUE LIMIT 1;
    IF v_target.id IS NULL THEN RAISE EXCEPTION 'Active member not found.'; END IF;
    IF v_target.role = 'OWNER' THEN RAISE EXCEPTION 'Use transfer_family_ownership to change the family owner.'; END IF;

    UPDATE public.family_members SET role = v_clean_role, updated_at = NOW() WHERE id = v_target.id;
    PERFORM public.log_family_audit(p_family_id, v_uid, 'MEMBER_ROLE_UPDATED', 'family_members', v_target.id, jsonb_build_object('target_user_id', p_target_user_id, 'new_role', v_clean_role));
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION public.remove_family_member(p_family_id UUID, p_target_user_id UUID)
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
    SELECT * INTO v_target FROM public.family_members
    WHERE family_id = p_family_id AND user_id = p_target_user_id AND status = 'ACTIVE' AND is_active = TRUE LIMIT 1;
    IF v_target.id IS NULL THEN RAISE EXCEPTION 'Active member not found.'; END IF;
    IF v_target.role = 'OWNER' THEN RAISE EXCEPTION 'Owner cannot be removed from the family.'; END IF;

    UPDATE public.family_members SET status = 'REMOVED', is_active = FALSE, updated_at = NOW() WHERE id = v_target.id;
    INSERT INTO public.family_messages (family_id, sender_user_id, sender_name, sender_avatar, message_type, body)
    VALUES (p_family_id, v_uid, v_target.display_name, v_target.avatar_emoji, 'SYSTEM', format('%s was removed from the family.', v_target.display_name));
    PERFORM public.log_family_audit(p_family_id, v_uid, 'MEMBER_REMOVED', 'family_members', v_target.id, jsonb_build_object('target_user_id', p_target_user_id));
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION public.transfer_family_ownership(p_family_id UUID, p_new_owner_user_id UUID)
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
    IF p_new_owner_user_id = v_uid THEN RAISE EXCEPTION 'Selected member is already the owner.'; END IF;

    SELECT * INTO v_new_owner FROM public.family_members
    WHERE family_id = p_family_id AND user_id = p_new_owner_user_id AND status = 'ACTIVE' AND is_active = TRUE LIMIT 1;
    IF v_new_owner.id IS NULL THEN RAISE EXCEPTION 'Target user must be an active member of this family.'; END IF;

    PERFORM set_config('app.family_rpc_bypass', 'on', true);
    UPDATE public.family_members SET role = 'ADMIN', updated_at = NOW() WHERE family_id = p_family_id AND user_id = v_uid AND role = 'OWNER';
    UPDATE public.family_members SET role = 'OWNER', updated_at = NOW() WHERE id = v_new_owner.id;
    UPDATE public.families SET owner_id = p_new_owner_user_id, updated_at = NOW() WHERE id = p_family_id;

    INSERT INTO public.family_messages (family_id, sender_user_id, sender_name, sender_avatar, message_type, body)
    VALUES (p_family_id, v_uid, v_new_owner.display_name, v_new_owner.avatar_emoji, 'SYSTEM', format('Family ownership transferred to %s.', v_new_owner.display_name));
    PERFORM public.log_family_audit(p_family_id, v_uid, 'OWNERSHIP_TRANSFERRED', 'families', p_family_id, jsonb_build_object('new_owner', p_new_owner_user_id));
    RETURN TRUE;
END;
$$;

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
    IF v_uid IS NULL THEN RAISE EXCEPTION 'Authentication required.'; END IF;
    SELECT * INTO v_member FROM public.family_members
    WHERE family_id = p_family_id AND user_id = v_uid AND status = 'ACTIVE' AND is_active = TRUE LIMIT 1;
    IF v_member.id IS NULL THEN RAISE EXCEPTION 'You are not an active member of this family.'; END IF;
    IF v_member.role = 'OWNER' THEN
        RAISE EXCEPTION 'Owner must transfer ownership to another member or close the family before leaving.';
    END IF;

    UPDATE public.family_members SET status = 'LEFT', is_active = FALSE, updated_at = NOW() WHERE id = v_member.id;
    INSERT INTO public.family_messages (family_id, sender_user_id, sender_name, sender_avatar, message_type, body)
    VALUES (p_family_id, v_uid, v_member.display_name, v_member.avatar_emoji, 'SYSTEM', format('%s left the family.', v_member.display_name));
    PERFORM public.log_family_audit(p_family_id, v_uid, 'MEMBER_LEFT', 'family_members', v_member.id, '{}'::jsonb);
    RETURN TRUE;
END;
$$;

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
    UPDATE public.families SET is_deleted = TRUE, updated_at = NOW() WHERE id = p_family_id;
    UPDATE public.family_members SET status = 'LEFT', is_active = FALSE, updated_at = NOW() WHERE family_id = p_family_id AND status = 'ACTIVE';
    UPDATE public.family_invitations SET status = 'REVOKED', updated_at = NOW() WHERE family_id = p_family_id AND status = 'PENDING';
    PERFORM public.log_family_audit(p_family_id, v_uid, 'FAMILY_CLOSED', 'families', p_family_id, '{}'::jsonb);
    RETURN TRUE;
END;
$$;

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
    IF v_uid IS NULL THEN RETURN NULL; END IF;
    SELECT fm.* INTO v_member FROM public.family_members fm
    JOIN public.families f ON f.id = fm.family_id
    WHERE fm.user_id = v_uid AND fm.status = 'ACTIVE' AND fm.is_active = TRUE AND NOT f.is_deleted LIMIT 1;
    IF v_member.id IS NULL THEN RETURN NULL; END IF;
    SELECT * INTO v_family FROM public.families WHERE id = v_member.family_id LIMIT 1;
    SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.joined_at ASC), '[]'::jsonb) INTO v_members
    FROM public.family_members m WHERE m.family_id = v_family.id AND m.status = 'ACTIVE' AND m.is_active = TRUE;
    RETURN jsonb_build_object('family', to_jsonb(v_family), 'membership', to_jsonb(v_member), 'members', v_members);
END;
$$;

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
    SET name = v_clean_name, avatar_emoji = v_emoji, family_label = v_label, updated_at = NOW()
    WHERE id = p_family_id AND NOT is_deleted
    RETURNING * INTO v_family;
    IF v_family.id IS NULL THEN
        RAISE EXCEPTION 'Family not found.';
    END IF;
    PERFORM public.log_family_audit(p_family_id, v_uid, 'FAMILY_PROFILE_UPDATED', 'families', p_family_id, jsonb_build_object('name', v_clean_name, 'family_label', v_label));
    RETURN to_jsonb(v_family);
END;
$$;

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
    SET display_name = v_clean_name, name = v_clean_name, member_label = v_clean_label,
        avatar_emoji = coalesce(NULLIF(trim(p_avatar_emoji), ''), avatar_emoji), updated_at = NOW()
    WHERE family_id = p_family_id AND user_id = p_target_user_id AND status = 'ACTIVE'
    RETURNING * INTO v_member;
    IF v_member.id IS NULL THEN
        RAISE EXCEPTION 'Active target member not found.';
    END IF;
    PERFORM public.log_family_audit(p_family_id, v_uid, 'MEMBER_PROFILE_UPDATED', 'family_members', v_member.id, jsonb_build_object('display_name', v_clean_name, 'member_label', v_clean_label));
    RETURN to_jsonb(v_member);
END;
$$;

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
