package com.smithswz.tsphone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smithswz.tsphone.BuildConfig
import com.smithswz.tsphone.R
import com.smithswz.tsphone.TSPhoneApp
import com.smithswz.tsphone.data.prefs.CodecQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as TSPhoneApp
    val vm: SettingsViewModel = viewModel { SettingsViewModel(app.container.settingsRepository) }
    val codecQuality by vm.codecQuality.collectAsStateWithLifecycle()
    val vadSensitivity by vm.vadSensitivity.collectAsStateWithLifecycle()
    val speakerOn by vm.speakerOn.collectAsStateWithLifecycle()
    val inputGain by vm.inputGain.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.title_settings)) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(stringResource(R.string.section_audio), style = MaterialTheme.typography.titleMedium)

            // Codec quality (voice / music)
            Column {
                Text(stringResource(R.string.setting_codec_quality))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = codecQuality == CodecQuality.VOICE,
                        onClick = { vm.setCodecQuality(CodecQuality.VOICE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(stringResource(R.string.codec_voice)) }
                    SegmentedButton(
                        selected = codecQuality == CodecQuality.MUSIC,
                        onClick = { vm.setCodecQuality(CodecQuality.MUSIC) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.codec_music)) }
                }
            }

            // VAD sensitivity
            Column {
                Text(stringResource(R.string.setting_vad_sensitivity, vadSensitivity))
                Slider(
                    value = vadSensitivity.toFloat(),
                    onValueChange = { vm.setVadSensitivity(it.toInt()) },
                    valueRange = 0f..100f
                )
            }

            // Speaker output
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setting_speaker), Modifier.weight(1f))
                Switch(checked = speakerOn, onCheckedChange = { vm.setSpeakerOn(it) })
            }

            // Input gain
            Column {
                Text(stringResource(R.string.setting_input_gain, "%.1fx".format(inputGain)))
                Slider(
                    value = inputGain,
                    onValueChange = { vm.setInputGain(it) },
                    valueRange = 0.5f..4.0f
                )
            }

            Text(stringResource(R.string.section_about), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.setting_version, BuildConfig.VERSION_NAME))
        }
    }
}
