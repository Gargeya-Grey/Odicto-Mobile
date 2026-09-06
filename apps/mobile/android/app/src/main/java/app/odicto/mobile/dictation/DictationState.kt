package app.odicto.mobile.dictation

sealed interface DictationState {
    data object Idle : DictationState
    data class Ready(val editorSession: Long) : DictationState
    data class Recording(val editorSession: Long) : DictationState
    data class Processing(val editorSession: Long) : DictationState
    data class Completed(val editorSession: Long) : DictationState
    data class SavedToHistory(val reason: String) : DictationState
    data class Failed(val reason: String) : DictationState
}
