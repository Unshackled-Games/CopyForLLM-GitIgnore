package com.unshackledgames.copyforllmplus

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class CopyForLLMAction : DumbAwareAction(
    "Copy for LLM",
    "Collect selected files and copy their contents for LLM use",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return notify(project = null, "No project found.", NotificationType.WARNING)
        val dataContext: DataContext = e.dataContext
        val settings = service<CopyForLLMSettings>()

        val candidates = FileCollector.collectAllCandidateFiles(project, dataContext)
        if (candidates.isEmpty()) {
            notify(project, "No files found. Select files or directories in Project View, or open a file in the editor.", NotificationType.WARNING)
            return
        }

        val filtered = FileCollector.filterBySettings(settings, candidates)
        if (filtered.isEmpty()) {
            val mode = if (settings.isIncludeMode()) "INCLUDE" else "EXCLUDE"
            notify(project, "After $mode filtering there are no files to copy.", NotificationType.INFORMATION)
            return
        }

        val text = ReadAction.compute<String, RuntimeException> {
            val base = project.basePath ?: ""
            buildString {
                filtered.forEach { vf ->
                    appendLine("===== ${relPath(base, vf.path)} =====")
                    appendLine(VfsUtilCore.loadText(vf))
                    appendLine()
                }
            }
        }

        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(project, "Copied ${filtered.size} file(s) to clipboard.", NotificationType.INFORMATION)
    }

    private fun relPath(base: String, full: String): String {
        if (base.isBlank()) return full
        return if (full.startsWith(base)) full.removePrefix(base).trimStart('/', '\\') else full
    }

    private fun notify(project: Project?, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CopyForLLM")
            .createNotification(msg, type)
            .notify(project)
    }
}
