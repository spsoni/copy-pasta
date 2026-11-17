package info.suryasoni.copy_pasta.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import info.suryasoni.copy_pasta.CopyPastaBundle
import org.jetbrains.annotations.Nls

internal object Notifier {
    private const val GROUP_ID = "Copy-Pasta"

    fun notify(project: Project?, @Nls content: String, type: NotificationType, vararg actions: NotificationAction) {
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(CopyPastaBundle.message("notification.title"), content, type)
        actions.forEach(notification::addAction)
        notification.notify(project)
    }

    fun error(project: Project?, @Nls content: String) = notify(project, content, NotificationType.ERROR)
}
