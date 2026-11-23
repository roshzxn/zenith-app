-- =============================================================================
-- ZENITH FINANCE: 100% SAFE QUICK SCHEMA UPDATE QUERY
-- Run this in your Supabase SQL Editor (Dashboard -> SQL Editor -> New Query)
-- =============================================================================

-- 1. Ensure new social & creator attribution columns exist on transactions
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS paid_by_member_id UUID;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS paid_by_name TEXT DEFAULT 'Member';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS finance_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES public.families(id) ON DELETE CASCADE;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Safely remove restrictive foreign keys that can block offline/unlinked members
DO $$
BEGIN
    ALTER TABLE public.family_members DROP CONSTRAINT IF EXISTS family_members_user_id_fkey;
    ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_paid_by_member_id_fkey;
    ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_user_id_fkey;
EXCEPTION WHEN others THEN null;
END $$;

-- 3. Ensure family_members has all required columns
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS display_name TEXT DEFAULT 'Member';
ALTER TABLE public.family_members ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- 4. Enable Realtime Replication for instant multi-device live feed
ALTER TABLE public.transactions REPLICA IDENTITY FULL;
ALTER TABLE public.family_members REPLICA IDENTITY FULL;
ALTER TABLE public.families REPLICA IDENTITY FULL;

-- 5. Add tables to supabase_realtime publication if not already present
DO $$
DECLARE
    t text;
    target_tables text[] := ARRAY['families', 'family_members', 'transactions'];
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
