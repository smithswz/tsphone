package com.smithswz.tsphone.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.github.manevolent.ts3j.identity.LocalIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface IdentityState {
    data object Generating : IdentityState
    data class Ready(val uniqueId: String) : IdentityState
    data class Failed(val message: String) : IdentityState
}

/**
 * Owns the single global TS3 identity. Generates it on first launch
 * (background thread — the key derivation takes a few seconds) and persists it
 * as the standard TS3 INI identity string, restorable by `LocalIdentity.read`.
 */
class IdentityRepository(context: Context, private val scope: CoroutineScope) {

    private val dataStore = context.dataStore

    private val _state = MutableStateFlow<IdentityState>(IdentityState.Generating)
    val state: StateFlow<IdentityState> = _state.asStateFlow()

    init {
        scope.launch {
            _state.value = try {
                val stored = dataStore.data.first()[Keys.IDENTITY_EXPORT]
                if (stored != null) {
                    IdentityState.Ready(LocalIdentity.read(stored.byteInputStream()).uid.toBase64())
                } else {
                    val identity = withContext(Dispatchers.Default) {
                        LocalIdentity.generateNew(IDENTITY_SECURITY_LEVEL)
                    }
                    dataStore.edit { it[Keys.IDENTITY_EXPORT] = identity.export() }
                    IdentityState.Ready(identity.uid.toBase64())
                }
            } catch (e: Exception) {
                IdentityState.Failed(e.message ?: "identity generation failed")
            }
        }
    }

    /** Loads the stored identity; null if not generated yet. */
    suspend fun load(): LocalIdentity? {
        val stored = dataStore.data.first()[Keys.IDENTITY_EXPORT] ?: return null
        return withContext(Dispatchers.Default) { LocalIdentity.read(stored.byteInputStream()) }
    }

    companion object {
        /** Level 12: fast enough on a phone, satisfies typical server minimums. */
        private const val IDENTITY_SECURITY_LEVEL = 12
    }
}
