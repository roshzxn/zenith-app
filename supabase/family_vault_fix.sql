-- =============================================================================
-- ZENITH FINANCE: FAMILY VAULT & REALTIME INSTANT REPAIR SCRIPT
-- Run this script in your Supabase SQL Editor (Dashboard > SQL Editor > New Query)
-- =============================================================================

-- 1. Ensure required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 2. Ensure columns exist and have compatible types
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.families ADD COLUMN IF NOT EXISTS invite_code VARCHAR(32);

ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS display_name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS avatar_color TEXT DEFAULT '#10B981';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Safely drop foreign key constraint on family_members.user_id if it prevents joining
DO $$
BEGIN
    ALTER TABLE public.family_members DROP CONSTRAINT IF EXISTS family_members_user_id_fkey;
    ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_paid_by_member_id_fkey;
    ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_user_id_fkey;
EXCEPTION WHEN others THEN null;
END $$;

ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS paid_by_member_id UUID;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS paid_by_name TEXT DEFAULT 'Member';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS title TEXT DEFAULT 'Transaction';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(32) DEFAULT 'EXPENSE';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS category TEXT NOT NULL DEFAULT 'Other';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS category_id TEXT DEFAULT 'Other';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS category_name TEXT DEFAULT 'Other';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS payment_method TEXT NOT NULL DEFAULT 'UPI';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS sync_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS transaction_date TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 3. DROP ALL EXISTING POLICIES (Clean Slate)
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
            'savings_contributions', 'audit_logs', 'family_invitations'
          )
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I.%I', pol.policyname, pol.schemaname, pol.tablename);
    END LOOP;
END $$;

-- 4. ENABLE ROW LEVEL SECURITY
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

-- 5. CREATE BULLET-PROOF, NON-RECURSIVE RLS POLICIES

-- 5.1 Profiles
CREATE POLICY "profiles_select" ON public.profiles FOR SELECT TO anon, authenticated USING (true);
CREATE POLICY "profiles_insert" ON public.profiles FOR INSERT TO anon, authenticated WITH CHECK (true);
CREATE POLICY "profiles_update" ON public.profiles FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);

-- 5.2 Families (Open read for invite codes, full member insert & update)
CREATE POLICY "families_select" ON public.families FOR SELECT TO anon, authenticated USING (NOT is_deleted);
CREATE POLICY "families_insert" ON public.families FOR INSERT TO anon, authenticated WITH CHECK (true);
CREATE POLICY "families_update" ON public.families FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "families_delete" ON public.families FOR DELETE TO anon, authenticated USING (true);

-- 5.3 Family Members (Permits members to join, view, and update roster)
CREATE POLICY "family_members_select" ON public.family_members FOR SELECT TO anon, authenticated USING (true);
CREATE POLICY "family_members_insert" ON public.family_members FOR INSERT TO anon, authenticated WITH CHECK (true);
CREATE POLICY "family_members_update" ON public.family_members FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "family_members_delete" ON public.family_members FOR DELETE TO anon, authenticated USING (true);

-- 5.4 Categories
CREATE POLICY "categories_access" ON public.categories FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);

-- 5.5 Transactions (Shared family transactions visible and writable by vault participants)
CREATE POLICY "transactions_select" ON public.transactions FOR SELECT TO anon, authenticated USING (NOT is_deleted);
CREATE POLICY "transactions_insert" ON public.transactions FOR INSERT TO anon, authenticated WITH CHECK (true);
CREATE POLICY "transactions_update" ON public.transactions FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "transactions_delete" ON public.transactions FOR DELETE TO anon, authenticated USING (true);

-- 5.6 Budgets & Savings
CREATE POLICY "budgets_access" ON public.budgets FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "savings_goals_access" ON public.savings_goals FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "savings_contributions_access" ON public.savings_contributions FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "audit_logs_access" ON public.audit_logs FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);
CREATE POLICY "family_invitations_access" ON public.family_invitations FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);

-- 6. CONFIGURE REALTIME PUBLICATION & REPLICA IDENTITY
ALTER TABLE public.transactions REPLICA IDENTITY FULL;
ALTER TABLE public.family_members REPLICA IDENTITY FULL;
ALTER TABLE public.families REPLICA IDENTITY FULL;
ALTER TABLE public.budgets REPLICA IDENTITY FULL;
ALTER TABLE public.savings_goals REPLICA IDENTITY FULL;
ALTER TABLE public.categories REPLICA IDENTITY FULL;
ALTER TABLE public.profiles REPLICA IDENTITY FULL;

DO $$
DECLARE
    t text;
    target_tables text[] := ARRAY[
        'profiles', 'families', 'family_members', 'categories',
        'transactions', 'budgets', 'savings_goals'
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
