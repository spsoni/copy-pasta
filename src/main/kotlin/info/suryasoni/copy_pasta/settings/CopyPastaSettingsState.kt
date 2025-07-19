package info.suryasoni.copy_pasta.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CopyPastaSettings", storages = [Storage("CopyPastaSettings.xml")])
@Service
class CopyPastaSettingsState : PersistentStateComponent<CopyPastaSettingsState.State> {
    private var myState = State()

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }

    data class State(
        var excludePatterns: String = "*.pyc,*.pyo,__pycache__/*,.git/*,venv/*,.venv/*,build/*,dist/*,*.egg-info/*,*.log,htmlcov/*,.coverage*,.vscode/*,.idea/*,*~,*.swp,*.bak,.DS_Store",
        var maxBase64Size: Int = 1 * 1024 * 1024, // 1 MB
        var encryptionKey: String = "default_key_123456", // Predefined key (should be securely generated and stored)
        var enableDecryption: Boolean = true, // Toggle for enabling/disabling decryption
        var enableEncryption: Boolean = true  // Toggle for enabling/disabling encryption
    )
}
