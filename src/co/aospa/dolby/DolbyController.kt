/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import co.aospa.dolby.DolbyConstants.Companion.dlog
import co.aospa.dolby.DolbyConstants.DsParam

internal class DolbyController private constructor(private val context: Context) {
    private var dolbyEffect: DolbyAudioEffect? = createEffectSafely()
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val handler = Handler(context.mainLooper)
    private val stereoWideningSupported =
        context.resources.getBoolean(R.bool.dolby_stereo_widening_supported)
    private val speakerVirtSupported =
        context.resources.getBoolean(R.bool.dolby_speaker_virtualizer_supported)

    private fun createEffectSafely(): DolbyAudioEffect? {
        return try {
            DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DolbyAudioEffect", e)
            null
        }
    }

    // Restore current profile on every media session
    private val playbackCallback =
        object : AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                try {
                    val isPlaying =
                        configs.any {
                            it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                        }
                    dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
                    if (isPlaying) setCurrentProfile()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onPlaybackConfigChanged", e)
                }
            }
        }

    // Restore current profile on audio device change
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                try {
                    dlog(TAG, "onAudioDevicesAdded")
                    setCurrentProfile()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onAudioDevicesAdded", e)
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                try {
                    dlog(TAG, "onAudioDevicesRemoved")
                    setCurrentProfile()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onAudioDevicesRemoved", e)
                }
            }
        }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            try {
                if (value) {
                    audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
                } else {
                    audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                    audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting registerCallbacks=$value", e)
            }
        }

    var dsOn: Boolean
        get() = (dolbyEffect?.dsOn ?: false).also { dlog(TAG, "getDsOn: $it") }
        set(value) {
            dlog(TAG, "setDsOn: $value")
            if (checkEffect()) {
                dolbyEffect?.dsOn = value
            }
            registerCallbacks = value
            if (value) setCurrentProfile()
        }

    var profile: Int
        get() = (dolbyEffect?.profile ?: 0).also { dlog(TAG, "getProfile: $it") }
        set(value) {
            dlog(TAG, "setProfile: $value")
            if (checkEffect()) {
                dolbyEffect?.profile = value
            }
        }

    init {
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")

        // Restore our main settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        dsOn = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)

        context.resources
            .getStringArray(R.array.dolby_profile_values)
            .map { it.toInt() }
            .forEach { profile ->
                // Reset dolby first to prevent it from loading bad settings
                dolbyEffect?.resetProfileSpecificSettings(profile)
                // Now restore our profile-specific settings
                restoreSettings(profile)
            }

        // Finally restore the current profile.
        setCurrentProfile()
    }

    private fun restoreSettings(profile: Int) {
        dlog(TAG, "restoreSettings(profile=$profile)")
        val prefs = context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
        setPreset(prefs.getString(DolbyConstants.PREF_PRESET, getPreset(profile))!!, profile)
        setIeqPreset(
            prefs.getString(DolbyConstants.PREF_IEQ, "1" /* balanced */)!!.toInt(),
            profile,
        )
        val virtEnabled = prefs.getBoolean(
            DolbyConstants.PREF_VIRTUALIZER,
            prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, getHeadphoneVirtEnabled(profile)),
        )
        setVirtualizerEnabled(virtEnabled, profile)
        setStereoWideningAmount(
            prefs
                .getString(
                    DolbyConstants.PREF_STEREO,
                    "0",
                )!!
                .toInt(),
            profile,
        )
        setDialogueEnhancerAmount(
            prefs
                .getString(
                    DolbyConstants.PREF_DIALOGUE,
                    "0",
                )!!
                .toInt(),
            profile,
        )
        setBassEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_BASS, false),
            profile,
        )
        setVolumeLevelerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_VOLUME, false),
            profile,
        )
    }

    private fun checkEffect(): Boolean {
        try {
            val effect = dolbyEffect
            if (effect == null || !effect.hasControl()) {
                Log.w(TAG, "lost control, recreating effect")
                try {
                    effect?.release()
                } catch (ignored: Exception) {
                }
                dolbyEffect = createEffectSafely()
            }
            return dolbyEffect != null
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkEffect", e)
            dolbyEffect = null
            return false
        }
    }

    private fun setCurrentProfile() {
        dlog(TAG, "setCurrentProfile")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentProfile = prefs.getString(DolbyConstants.PREF_PROFILE, "0" /*dynamic*/)!!.toInt()
        profile = currentProfile
        if (!speakerVirtSupported) {
            setSpeakerVirtEnabled(false, currentProfile)
        }
    }

    fun setDsOnAndPersist(dsOn: Boolean) {
        this.dsOn = dsOn
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(DolbyConstants.PREF_ENABLE, dsOn)
            .apply()
    }

    fun getProfileName(): String? {
        val profile = (dolbyEffect?.profile ?: 0).toString()
        val profiles = context.resources.getStringArray(R.array.dolby_profile_values)
        val profileIndex = profiles.indexOf(profile)
        dlog(TAG, "getProfileName: profile=$profile index=$profileIndex")
        return if (profileIndex == -1) null
        else context.resources.getStringArray(R.array.dolby_profile_entries)[profileIndex]
    }

    fun resetProfileSpecificSettings() {
        dlog(TAG, "resetProfileSpecificSettings")
        if (checkEffect()) {
            dolbyEffect?.resetProfileSpecificSettings()
            if (!speakerVirtSupported) {
                setSpeakerVirtEnabled(false, profile)
            }
        }
        context.deleteSharedPreferences("profile_$profile")
    }

    fun getPreset(profile: Int = this.profile): String {
        val effect = dolbyEffect ?: return List(20) { 0 }.joinToString(",")
        val gains = effect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
        return gains.joinToString(separator = ",").also { dlog(TAG, "getPreset: $it") }
    }

    fun setPreset(value: String, profile: Int = this.profile) {
        dlog(TAG, "setPreset: $value")
        if (!checkEffect()) return
        val gains = value.split(",").map { it.toInt() }.toIntArray()
        dolbyEffect?.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
    }

    fun getPresetName(): String {
        val presets = context.resources.getStringArray(R.array.dolby_preset_values)
        val presetIndex = presets.indexOf(getPreset())
        return if (presetIndex == -1) {
            "Custom"
        } else {
            context.resources.getStringArray(R.array.dolby_preset_entries)[presetIndex]
        }
    }

    fun getVirtualizerEnabled(profile: Int = this.profile) =
        getHeadphoneVirtEnabled(profile)

    fun setVirtualizerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVirtualizerEnabled: $value")
        setHeadphoneVirtEnabled(value, profile)
        setSpeakerVirtEnabled(if (speakerVirtSupported) value else false, profile)
    }

    fun getHeadphoneVirtEnabled(profile: Int = this.profile) =
        dolbyEffect?.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile) ?: false

    fun setHeadphoneVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setHeadphoneVirtEnabled: $value")
        if (!checkEffect()) return
        dolbyEffect?.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, profile)
    }

    fun getSpeakerVirtEnabled(profile: Int = this.profile) =
        if (!speakerVirtSupported) false
        else dolbyEffect?.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile) ?: false

    fun setSpeakerVirtEnabled(value: Boolean, profile: Int = this.profile) {
        if (!speakerVirtSupported && value) {
            dlog(TAG, "speaker virtualizer not supported, ignoring enabled=true")
            return
        }
        dlog(TAG, "setSpeakerVirtEnabled: $value")
        if (!checkEffect()) return
        dolbyEffect?.setDapParameter(
            DsParam.SPEAKER_VIRTUALIZER,
            if (speakerVirtSupported) value else false,
            profile,
        )
    }

    fun getBassEnhancerEnabled(profile: Int = this.profile) =
        dolbyEffect?.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile) ?: false

    fun setBassEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setBassEnhancerEnabled: $value")
        if (!checkEffect()) return
        dolbyEffect?.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, profile)
    }

    fun getVolumeLevelerEnabled(profile: Int = this.profile) =
        dolbyEffect?.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile) ?: false

    fun setVolumeLevelerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVolumeLevelerEnabled: $value")
        if (!checkEffect()) return
        dolbyEffect?.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, profile)
    }

    fun getStereoWideningAmount(profile: Int = this.profile) =
        if (!stereoWideningSupported) {
            0
        } else {
            dolbyEffect?.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile) ?: 0
        }

    fun setStereoWideningAmount(value: Int, profile: Int = this.profile) {
        if (!stereoWideningSupported || !checkEffect()) return
        dlog(TAG, "setStereoWideningAmount: $value")
        dolbyEffect?.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, value, profile)
    }

    fun getDialogueEnhancerAmount(profile: Int = this.profile): Int {
        val effect = dolbyEffect ?: return 0
        val enabled = effect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
        return if (enabled) {
            effect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
        } else 0
    }

    fun setDialogueEnhancerAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerAmount: $value")
        if (!checkEffect()) return
        dolbyEffect?.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, (value > 0), profile)
        dolbyEffect?.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, value, profile)
    }

    fun getIeqPreset(profile: Int = this.profile) =
        dolbyEffect?.getDapParameterInt(DsParam.IEQ_PRESET, profile) ?: 1

    fun setIeqPreset(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setIeqPreset: $value")
        if (!checkEffect()) return
        dolbyEffect?.setDapParameter(DsParam.IEQ_PRESET, value, profile)
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100

        @Volatile private var instance: DolbyController? = null

        fun getInstance(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: DolbyController(context).also { instance = it }
                }
    }
}
