package com.nuvio.app.features.anime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AnimeModePreference {
    private val _isAnimeMode = MutableStateFlow(false)
    val isAnimeMode: StateFlow<Boolean> = _isAnimeMode.asStateFlow()

    fun setAnimeMode(enabled: Boolean) {
        _isAnimeMode.value = enabled
    }

    fun toggleAnimeMode() {
        _isAnimeMode.value = !_isAnimeMode.value
    }
}
