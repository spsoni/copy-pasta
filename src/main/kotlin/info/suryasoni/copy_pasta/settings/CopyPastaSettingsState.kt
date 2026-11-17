package info.suryasoni.copy_pasta.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CopyPastaSettings", storages = [Storage("CopyPastaSettings.xml")])
@Service
class CopyPastaSettingsState : PersistentStateComponent<CopyPastaSettingsState.State> {
    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    data class State(
        var excludePatterns: String = DEFAULT_EXCLUDES,
        var maxBase64Size: Int = 1024 * 1024, // max length of the Base64 clipboard text
        var encryptionKey: String = "default_key_123456", // shared default; users should set their own
        var enableDecryption: Boolean = true,
        var enableEncryption: Boolean = true,
    )

    companion object {
        const val DEFAULT_EXCLUDES =
            ".git/*,.idea/*,.vscode/*,.DS_Store,*~,*.swp,*.bak,*.log," +
                "build/*,dist/*,node_modules/*," +
                "venv/*,.venv/*,__pycache__/*,*.pyc,*.pyo,*.egg-info/*,htmlcov/*,.coverage*"
    }
}
