package com.zenith.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Verifies SQL migration & schema security contracts for Phase 2 Family Mode + Group Chat.
 */
class SupabaseMigrationContractTest {

    private fun resolveProjectRoot(): File {
        val direct = File(".")
        return if (File(direct, "supabase/schema.sql").exists()) direct else File("..")
    }

    @Test
    fun testPhase2MigrationAndMasterSchemaDefineRequiredTablesAndConstraints() {
        val root = resolveProjectRoot()
        val migrationFile = File(root, "supabase/migrations/002_family_mode.sql")
        val deleteFixMigration = File(root, "supabase/migrations/003_delete_and_clear_fix.sql")
        val masterSchemaFile = File(root, "supabase/schema.sql")

        assertTrue("Expected versioned migration 002_family_mode.sql", migrationFile.exists())
        assertTrue("Expected upgrade migration 003_delete_and_clear_fix.sql", deleteFixMigration.exists())
        assertTrue("Expected master supabase/schema.sql", masterSchemaFile.exists())

        val migration = migrationFile.readText()
        val master = masterSchemaFile.readText()

        val requiredTables = listOf(
            "public.families",
            "public.family_members",
            "public.family_invitations",
            "public.family_categories",
            "public.family_transactions",
            "public.family_budgets",
            "public.family_messages",
            "public.family_audit_logs"
        )

        requiredTables.forEach { table ->
            assertTrue("Migration missing CREATE TABLE $table", migration.contains("CREATE TABLE IF NOT EXISTS $table"))
            assertTrue("Master schema missing CREATE TABLE $table", master.contains("CREATE TABLE IF NOT EXISTS $table"))
            assertTrue("Migration missing ENABLE ROW LEVEL SECURITY on $table", migration.contains("ALTER TABLE $table ENABLE ROW LEVEL SECURITY"))
            assertTrue("Master schema missing ENABLE ROW LEVEL SECURITY on $table", master.contains("ALTER TABLE $table ENABLE ROW LEVEL SECURITY"))
        }

        // Master schema also protects Personal profiles table
        assertTrue(master.contains("CREATE TABLE IF NOT EXISTS public.profiles"))
        assertTrue(master.contains("ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY"))

        // Database-level 1-family-per-user constraint + duplicate-tx/msg prevention
        listOf(migration, master).forEach { sql ->
            assertTrue("Missing active single-family unique index", sql.contains("uq_family_members_active_user"))
            assertTrue("Missing client_tx_id dedupe index", sql.contains("uq_family_transactions_client_id"))
            assertTrue("Missing client_msg_id dedupe index", sql.contains("uq_family_messages_client_id"))
            assertTrue("Missing avatar_emoji support on families", sql.contains("avatar_emoji"))
            assertTrue("Missing FROM anon privilege revocation", sql.contains("FROM anon"))
            assertFalse("service_role key or privilege must never appear in client/migration SQL", sql.contains("service_role", ignoreCase = true))
        }

        // Retired locked policies must be gone from master schema
        assertFalse("families_locked must not lock out new family RLS", master.contains("\"families_locked\""))
        assertFalse("family_members_locked must not lock out new member RLS", master.contains("\"family_members_locked\""))
        assertFalse("family_invitations_locked must not lock out invitations RLS", master.contains("\"family_invitations_locked\""))
    }

    @Test
    fun testPhase2AtomicRpcsSecurityHelpersAndRealtimePoliciesPresent() {
        val root = resolveProjectRoot()
        val migration = File(root, "supabase/migrations/002_family_mode.sql").readText()
        val master = File(root, "supabase/schema.sql").readText()

        val requiredFunctions = listOf(
            "public.is_family_member(",
            "public.is_family_admin(",
            "public.is_family_owner(",
            "public.create_family_atomic(",
            "public.create_family_invitation(",
            "public.accept_family_invitation(",
            "public.update_family_member_role(",
            "public.update_family_profile(",
            "public.update_family_member_profile(",
            "public.delete_my_personal_transaction(",
            "public.clear_my_personal_ledger(",
            "public.clear_family_transactions_atomic(",
            "public.remove_family_member(",
            "public.transfer_family_ownership(",
            "public.leave_family(",
            "public.close_family(",
            "public.get_my_active_family("
        )

        requiredFunctions.forEach { fn ->
            assertTrue("Migration missing function $fn", migration.contains("CREATE OR REPLACE FUNCTION $fn"))
            assertTrue("Master schema missing function $fn", master.contains("CREATE OR REPLACE FUNCTION $fn"))
        }

        listOf(migration, master).forEach { sql ->
            assertTrue("Missing private Realtime broadcast read policy", sql.contains("family_broadcast_read"))
            assertTrue("Missing private Realtime broadcast write policy", sql.contains("family_broadcast_write"))
            assertTrue("Missing Owner leave guard message", sql.contains("transfer ownership to another member or close the family"))
            assertTrue("Missing client_tx_id dedupe column", sql.contains("client_tx_id"))
            assertTrue("Missing family_label column", sql.contains("family_label"))
            assertTrue("Missing member_label column", sql.contains("member_label"))
            assertTrue("Missing custom_label column", sql.contains("custom_label"))
            assertTrue("Missing family_messages chat RLS policies", sql.contains("family_messages_select"))
            assertTrue("Missing SECURITY DEFINER helpers", sql.contains("SECURITY DEFINER"))
        }
    }

    @Test
    fun testAppCodeNeverContainsSupabaseServiceRoleKey() {
        val root = resolveProjectRoot()
        val kotlinSources = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .toList()
        assertTrue(kotlinSources.isNotEmpty())
        kotlinSources.forEach { file ->
            val content = file.readText()
            assertFalse(
                "App source ${file.name} must never reference service_role",
                content.contains("service_role", ignoreCase = true)
            )
        }
    }

    @Test
    fun testMigration004PublishReadinessContractsPresent() {
        val root = resolveProjectRoot()
        val m004File = File(root, "supabase/migrations/004_publish_readiness.sql")
        val master = File(root, "supabase/schema.sql").readText()
        assertTrue("Expected upgrade migration 004_publish_readiness.sql", m004File.exists())

        val m004 = m004File.readText()
        listOf(m004, master).forEach { sql ->
            assertTrue("Missing multi-use max_uses column on family_invitations", sql.contains("max_uses"))
            assertTrue("Missing multi-use uses_count column on family_invitations", sql.contains("uses_count"))
            assertTrue("Missing custom_period_name on budgets/family_budgets", sql.contains("custom_period_name"))
            assertTrue("Missing custom_label on transactions", sql.contains("custom_label"))
            assertTrue("Missing delete_my_account RPC", sql.contains("CREATE OR REPLACE FUNCTION public.delete_my_account()"))
            assertFalse("service_role must never appear in 004 or schema.sql", sql.contains("service_role", ignoreCase = true))
        }
    }
}
