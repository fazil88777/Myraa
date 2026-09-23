package com.myra.assistant.data

/**
 * A single chat message. Roles are "user", "model", "system".
 */
data class ChatMessage(
    val role: String,
    val text: String
)
