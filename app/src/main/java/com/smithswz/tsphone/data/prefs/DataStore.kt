package com.smithswz.tsphone.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore by preferencesDataStore(name = "tsphone_prefs")

object Keys {
    val IDENTITY_EXPORT = stringPreferencesKey("identity_export")
    val DEFAULT_NICKNAME = stringPreferencesKey("default_nickname")
    val CODEC_QUALITY = stringPreferencesKey("codec_quality")
    val VAD_SENSITIVITY = androidx.datastore.preferences.core.intPreferencesKey("vad_sensitivity")
    val SPEAKER_ON = androidx.datastore.preferences.core.booleanPreferencesKey("speaker_on")
    val INPUT_GAIN = androidx.datastore.preferences.core.floatPreferencesKey("input_gain")
    val MASTER_MUTED = androidx.datastore.preferences.core.booleanPreferencesKey("master_muted")
    val OUTPUT_MUTED = androidx.datastore.preferences.core.booleanPreferencesKey("output_muted")
}
