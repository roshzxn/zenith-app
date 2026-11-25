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
            'auto_pay_reminders'
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
    description TEXT,
    invite_code VARCHAR(32) UNIQUE NOT NULL DEFAULT ('FAM-' || upper(substring(replace(gen_random_uuid()::text, '-', ''), 1, 6))),
    created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE public.families ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Family Vault';
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS invite_code VARCHAR(32);
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- 2.3 FAMILY MEMBERS TABLE
CREATE TABLE IF NOT EXISTS public.family_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    user_id UUID,
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    name TEXT DEFAULT 'Member',
    display_name TEXT DEFAULT 'Member',
    avatar_color TEXT DEFAULT '#10B981',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS display_name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS avatar_color TEXT DEFAULT '#10B981';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Drop overly restrictive foreign key on user_id if it exists to allow unlinked members
DO $$
BEGIN
    ALTER TABLE public.family_members DROP CONSTRAINT IF EXISTS family_members_user_id_fkey;
EXCEPTION WHEN others THEN null;
END $$;

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

-- 2.8 AUDIT LOGS
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

-- 2.9 FAMILY INVITATIONS
CREATE TABLE IF NOT EXISTS public.family_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    invite_code VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    invited_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '7 days'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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

CREATE OR REPLACE FUNCTION public.sync_family_member_columns()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.name IS NOT NULL AND NEW.name <> '' THEN
        NEW.display_name := NEW.name;
    ELSIF NEW.display_name IS NOT NULL AND NEW.display_name <> '' THEN
        NEW.name := NEW.display_name;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sync_family_member_columns ON public.family_members;
CREATE TRIGGER trg_sync_family_member_columns
    BEFORE INSERT OR UPDATE ON public.family_members
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_family_member_columns();

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
CREATE INDEX IF NOT EXISTS idx_transactions_family_date ON public.transactions (family_id, transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_user_date ON public.transactions (user_id, transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_family_active ON public.transactions (family_id, transaction_date DESC) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_transactions_sync_version ON public.transactions (family_id, sync_version);
CREATE INDEX IF NOT EXISTS idx_family_members_family ON public.family_members (family_id);
CREATE INDEX IF NOT EXISTS idx_family_members_user ON public.family_members (user_id);
CREATE INDEX IF NOT EXISTS idx_families_invite_code ON public.families (invite_code);
CREATE INDEX IF NOT EXISTS idx_auto_pay_user ON public.auto_pay_reminders (user_id, next_due_date ASC);
CREATE INDEX IF NOT EXISTS idx_auto_pay_family ON public.auto_pay_reminders (family_id, next_due_date ASC);

-- =============================================================================
-- 6. STRICT PERSONAL-ONLY ROW LEVEL SECURITY (RLS) POLICIES
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

-- Revoke anonymous / unauthenticated table privileges explicitly
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
    public.auto_pay_reminders
FROM anon;

-- 6.1 Profiles: strict owner isolation (authenticated only)
CREATE POLICY "profiles_select" ON public.profiles
    FOR SELECT TO authenticated
    USING (auth.uid() = id);
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

-- 6.2 Retired Family Vault Tables: zero external access
CREATE POLICY "families_locked" ON public.families
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);
CREATE POLICY "family_members_locked" ON public.family_members
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);
CREATE POLICY "family_invitations_locked" ON public.family_invitations
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);
CREATE POLICY "audit_logs_locked" ON public.audit_logs
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);
CREATE POLICY "savings_contributions_locked" ON public.savings_contributions
    FOR ALL TO authenticated
    USING (false) WITH CHECK (false);

-- 6.3 Categories: global defaults readable by authenticated users; custom rows owner-isolated
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

-- 6.4 Personal Transactions: separate SELECT from mutations so soft-delete updates succeed
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

-- 6.5 Personal Budgets: owner-isolated with soft-delete update support
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

-- 6.6 Personal Savings Goals: owner-isolated with soft-delete update support
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

-- 6.7 Personal Auto-Pay Reminders: owner-isolated with soft-delete update support
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
-- 7. REALTIME SUBSCRIPTION CONFIGURATION (SAFE & IDEMPOTENT)
-- =============================================================================
ALTER TABLE public.transactions REPLICA IDENTITY FULL;
ALTER TABLE public.budgets REPLICA IDENTITY FULL;
ALTER TABLE public.savings_goals REPLICA IDENTITY FULL;
ALTER TABLE public.categories REPLICA IDENTITY FULL;
ALTER TABLE public.profiles REPLICA IDENTITY FULL;
ALTER TABLE public.auto_pay_reminders REPLICA IDENTITY FULL;

DO $$
DECLARE
    t text;
    target_tables text[] := ARRAY[
        'profiles', 'categories', 'transactions', 'budgets', 'savings_goals', 'auto_pay_reminders'
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

-- =============================================================================
-- 8. CLEAN UP RETIRED FAMILY RPCs
-- =============================================================================
DROP FUNCTION IF EXISTS public.create_family(TEXT);
DROP FUNCTION IF EXISTS public.join_family_by_code(VARCHAR);
DROP FUNCTION IF EXISTS public.get_family_dashboard(UUID);
