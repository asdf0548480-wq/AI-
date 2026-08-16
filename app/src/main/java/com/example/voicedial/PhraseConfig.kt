package com.example.voicedial

object PhraseConfig {

    enum class MediaAction {
        PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, PLAY_SPECIFIC
    }

    data class VoiceCommand(
        val phrase: String,
        val action: MediaAction
    )

    val COMMANDS = listOf(
        VoiceCommand("play music", MediaAction.PLAY),
        VoiceCommand("stop music", MediaAction.PAUSE),
        VoiceCommand("next song", MediaAction.NEXT),
        VoiceCommand("previous song", MediaAction.PREVIOUS),
        VoiceCommand("volume up", MediaAction.VOLUME_UP),
        VoiceCommand("volume down", MediaAction.VOLUME_DOWN),
        VoiceCommand("play song", MediaAction.PLAY_SPECIFIC)
    )

    fun buildGrammarJson(): String {
        val words = COMMANDS.map { "\"${it.phrase}\"" }
        return "[${words.joinToString(",")}, \"[unk]\"]"
    }
}
