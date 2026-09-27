package com.myra.assistant.util

import com.myra.assistant.ui.MainViewModel

/**
 * Holds the shared MainViewModel so background components
 * (e.g. the PC link server) can drive the voice session.
 */
object MyraBridge {
    var viewModel: MainViewModel? = null
}
