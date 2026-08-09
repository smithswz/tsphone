package com.smithswz.tsphone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smithswz.tsphone.data.prefs.CodecQuality
import com.smithswz.tsphone.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val codecQuality: StateFlow<CodecQuality> =
        settings.codecQuality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CodecQuality.VOICE)

    val vadSensitivity: StateFlow<Int> =
        settings.vadSensitivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 50)

    val speakerOn: StateFlow<Boolean> =
        settings.speakerOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val inputGain: StateFlow<Float> =
        settings.inputGain.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    fun setCodecQuality(value: CodecQuality) = viewModelScope.launch { settings.setCodecQuality(value) }
    fun setVadSensitivity(value: Int) = viewModelScope.launch { settings.setVadSensitivity(value) }
    fun setSpeakerOn(value: Boolean) = viewModelScope.launch { settings.setSpeakerOn(value) }
    fun setInputGain(value: Float) = viewModelScope.launch { settings.setInputGain(value) }
}
