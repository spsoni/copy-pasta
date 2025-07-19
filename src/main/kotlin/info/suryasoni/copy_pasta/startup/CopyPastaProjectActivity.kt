package info.suryasoni.copy_pasta.startup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CopyPastaProjectActivity : ProjectActivity {
    private val log = logger<CopyPastaProjectActivity>()

    override suspend fun execute(project: Project) {
        log.info("Copy-Pasta plugin initialized for project: ${project.name}")
    }
}
