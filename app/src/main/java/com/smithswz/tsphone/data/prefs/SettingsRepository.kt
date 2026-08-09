package com.smithswz.tsphone.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class CodecQuality(val label: String) {
    VOICE("voice"),
    MUSIC("music");

    companion object {
        fun from(value: String): CodecQuality =
            entries.firstOrNull { it.label == value } ?: VOICE
    }
}

class SettingsRepository(private val context: Context) {

    val defaultNickname: Flow<String> =
        context.dataStore.data.map { it[Keys.DEFAULT_NICKNAME] ?: "TS Phone User" }

    val codecQuality: Flow<CodecQuality> =
        context.dataStore.data.map { CodecQuality.from(it[Keys.CODEC_QUALITY] ?: "voice") }

    val vadSensitivity: Flow<Int> =
        context.dataStore.data.map { it[Keys.VAD_SENSITIVITY] ?: 50 }

    val speakerOn: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SPEAKER_ON] ?: true }

    val inputGain: Flow<Float> =
        context.dataStore.data.map { it[Keys.INPUT_GAIN] ?: 1.0f }

    val masterMuted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.MASTER_MUTED] ?: false }

    suspend fun setDefaultNickname(value: String) = context.dataStore.edit { it[Keys.DEFAULT_NICKNAME] = value }

    suspend fun setCodecQuality(value: CodecQuality) = context.dataStore.edit { it[Keys.CODEC_QUALITY] = value.label }

    suspend fun setVadSensitivity(value: Int) = context.dataStore.edit { it[Keys.VAD_SENSITIVITY] = value }

    suspend fun setSpeakerOn(value: Boolean) = context.dataStore.edit { it[Keys.SPEAKER_ON] = value }

    suspend fun setInputGain(value: Float) = context.dataStore.edit { it[Keys.INPUT_GAIN] = value }

    suspend fun setMasterMuted(value: Boolean) = context.dataStore.edit { it[Keys.MASTER_MUTED] = value }
}
