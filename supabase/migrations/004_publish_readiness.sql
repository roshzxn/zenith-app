-- =============================================================================
-- Migration 004: Zenith v2.1.0 Publish Readiness & Cloud Sync Parity
-- =============================================================================
-- 1. Multi-use Family Invite QR codes (max_uses / uses_count)
-- 2. Personal custom_label sync parity on public.transactions
-- 3. custom_period_name parity on public.budgets & public.family_budgets
-- 4. Mandatory in-app Account & Cloud Data Deletion RPC (delete_my_account)
-- =============================================================================

ALTER TABLE public.transactions
    ADD COLUMN IF NOT EXISTS custom_label TEXT NOT NULL DEFAULT '';

ALTER TABLE public.budgets
    ADD COLUMN IF NOT EXISTS custom_period_name TEXT NOT NULL DEFAULT '';

ALTER TABLE public.family_budgets
    ADD COLUMN IF NOT EXISTS custom_period_name TEXT NOT NULL DEFAULT '';

ALTER TABLE public.family_invitations
    ADD COLUMN IF NOT EXISTS max_uses INTEGER NOT NULL DEFAULT 5;

ALTER TABLE public.family_invitations
    ADD COLUMN IF NOT EXISTS uses_count INTEGER NOT NULL DEFAULT 0;

UPDATE public.family_invitations
SET uses_count = CASE WHEN status = 'ACCEPTED' THEN GREATEST(uses_count, 1) ELSE uses_count END
WHERE uses_count = 0 AND status = 'ACCEPTED';

DROP FUNCTION IF EXISTS public.create_family_invitation(UUID, TEXT, VARCHAR, INT);
CREATE OR REPLACE FUNCTION public.create_family_invitation(
    p_family_id UUID,
    p_invited_email TEXT DEFAULT '',
    p_role VARCHAR DEFAULT 'MEMBER',
    p_expires_hours INT DEFAULT 168,
    p_max_uses INT DEFAULT 5
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_clean_role VARCHAR(32) := upper(coalesce(nullif(trim(p_role), ''), 'MEMBER'));
    v_clean_email TEXT := lower(trim(coalesce(p_invited_email, '')));
    v_effective_max INT := CASE
        WHEN length(v_clean_email) > 0 THEN 1
        ELSE GREATEST(1, LEAST(coalesce(p_max_uses, 5), 25))
    END;
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
    INSERT INTO public.family_invitations (
        family_id, invited_email, email, invite_code, role, status, invited_by, expires_at, max_uses, uses_count
    )
    VALUES (
        p_family_id, v_clean_email, v_clean_email,
        v_code, v_clean_role, 'PENDING', v_uid,
        NOW() + make_interval(hours => GREATEST(1, LEAST(coalesce(p_expires_hours, 168), 720))),
        v_effective_max, 0
    )
    RETURNING * INTO v_inv;

    PERFORM public.log_family_audit(
        p_family_id, v_uid, 'INVITATION_CREATED', 'family_invitations', v_inv.id,
        jsonb_build_object('role', v_clean_role, 'max_uses', v_effective_max)
    );
    RETURN to_jsonb(v_inv);
END;
$$;

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
    v_next_uses INT;
    v_max_uses INT;
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

    v_max_uses := GREATEST(1, coalesce(v_inv.max_uses, 5));
    IF coalesce(v_inv.uses_count, 0) >= v_max_uses THEN
        UPDATE public.family_invitations SET status = 'ACCEPTED', updated_at = NOW() WHERE id = v_inv.id;
        RAISE EXCEPTION 'Invitation has reached its maximum usage limit.';
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

    v_next_uses := coalesce(v_inv.uses_count, 0) + 1;
    UPDATE public.family_invitations
    SET uses_count = v_next_uses,
        status = CASE WHEN v_next_uses >= v_max_uses THEN 'ACCEPTED' ELSE 'PENDING' END,
        accepted_by = v_uid,
        accepted_at = NOW(),
        updated_at = NOW()
    WHERE id = v_inv.id;

    INSERT INTO public.family_messages (family_id, sender_user_id, sender_name, sender_avatar, message_type, body)
    VALUES (v_family.id, v_uid, v_name, v_emoji, 'SYSTEM', format('%s joined the family.', v_name));

    PERFORM public.log_family_audit(
        v_family.id, v_uid, 'INVITATION_ACCEPTED', 'family_members', v_member.id,
        jsonb_build_object('invitation_id', v_inv.id, 'role', v_member.role, 'uses_count', v_next_uses, 'max_uses', v_max_uses)
    );
    RETURN jsonb_build_object('family', to_jsonb(v_family), 'membership', to_jsonb(v_member));
END;
$$;

CREATE OR REPLACE FUNCTION public.delete_my_account()
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_member public.family_members%ROWTYPE;
    v_active_count INT := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required to delete your account.';
    END IF;

    FOR v_member IN
        SELECT * FROM public.family_members
        WHERE user_id = v_uid AND status = 'ACTIVE' AND is_active = TRUE
    LOOP
        SELECT count(*) INTO v_active_count
        FROM public.family_members
        WHERE family_id = v_member.family_id AND status = 'ACTIVE' AND is_active = TRUE;

        IF v_member.role = 'OWNER' AND v_active_count > 1 THEN
            RAISE EXCEPTION 'Transfer family ownership or close your family group before deleting your account.';
        ELSIF v_member.role = 'OWNER' THEN
            PERFORM set_config('app.family_rpc_bypass', 'on', true);
            UPDATE public.families SET is_deleted = TRUE, updated_at = NOW() WHERE id = v_member.family_id;
            UPDATE public.family_members SET status = 'LEFT', is_active = FALSE, updated_at = NOW()
            WHERE family_id = v_member.family_id AND status = 'ACTIVE';
            UPDATE public.family_invitations SET status = 'REVOKED', updated_at = NOW()
            WHERE family_id = v_member.family_id AND status = 'PENDING';
        ELSE
            UPDATE public.family_members SET status = 'LEFT', is_active = FALSE, updated_at = NOW() WHERE id = v_member.id;
        END IF;
    END LOOP;

    DELETE FROM public.transactions WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    DELETE FROM public.budgets WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    DELETE FROM public.savings_goals WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    DELETE FROM public.auto_pay_reminders WHERE user_id = v_uid AND coalesce(finance_scope, 'PERSONAL') = 'PERSONAL';
    DELETE FROM public.categories WHERE user_id = v_uid AND is_default = FALSE;
    DELETE FROM public.profiles WHERE id = v_uid;

    BEGIN
        DELETE FROM auth.users WHERE id = v_uid;
    EXCEPTION WHEN insufficient_privilege THEN
        -- Hosted roles without auth schema delete privilege retain cleaned personal data
        NULL;
    END;

    RETURN TRUE;
END;
$$;

REVOKE ALL ON FUNCTION
    public.create_family_invitation(UUID, TEXT, VARCHAR, INT, INT),
    public.accept_family_invitation(TEXT, TEXT, VARCHAR, TEXT),
    public.delete_my_account()
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
    public.create_family_invitation(UUID, TEXT, VARCHAR, INT, INT),
    public.accept_family_invitation(TEXT, TEXT, VARCHAR, TEXT),
    public.delete_my_account()
TO authenticated;
