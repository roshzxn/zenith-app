package com.example.data.network

import com.example.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json

import android.content.Context

object SupabaseClientConfig {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val isConfigured: Boolean
        get() {
            val url = try { BuildConfig.SUPABASE_URL } catch (e: Exception) { "" }
            val key = try { BuildConfig.SUPABASE_ANON_KEY } catch (e: Exception) { "" }
            return url.isNotBlank() &&
                    url.startsWith("https://") &&
                    !url.contains("your-project-id.supabase.co") &&
                    key.isNotBlank() &&
                    !key.contains("your_supabase_anon_key_here")
        }

    val supabase: SupabaseClient by lazy {
        val safeUrl = if (isConfigured) BuildConfig.SUPABASE_URL else "https://fallback.zenith.vault.supabase.co"
        val safeKey = if (isConfigured) BuildConfig.SUPABASE_ANON_KEY else "fallback_anon_key_placeholder"

        createSupabaseClient(
            supabaseUrl = safeUrl,
            supabaseKey = safeKey
        ) {
            httpEngine = OkHttp.create()
            defaultSerializer = KotlinXSerializer(Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                isLenient = true
            })
            install(Auth) {
                val ctx = appContext
                sessionManager = if (ctx != null) {
                    SharedPreferencesSessionManager(ctx)
                } else {
                    MemorySessionManager()
                }
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            install(Realtime)
        }
    }
}
