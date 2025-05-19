package com.unshackledgames.copyforllmplus

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDirectory
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.pom.Navigatable
import java.awt.datatransfer.StringSelection
import java.util.regex.Pattern

class CopyForLlmAction : AnAction() {

    private val logger = Logger.getInstance(CopyForLlmAction::class.java)
    private val notificationGroupId = "CopyForLLMNotifications"

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val navigatables = e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)
        e.presentation.isEnabledAndVisible = project != null && !navigatables.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val navigatables = e.getData(CommonDataKeys.NAVIGATABLE_ARRAY) ?: return

        val selectedFiles = navigatables
            .mapNotNull { resolveVirtualFile(it) }
            .distinct()

        if (selectedFiles.isEmpty()) {
            showNotification(
                project,
                NotificationType.WARNING,
                "Could not determine the selected files/folders to copy."
            )
            return
        }

        // Load .gitignore patterns and extra extensions from settings
        val gitignorePatterns = loadGitignorePatterns(project)
        val extraExts = CopyForLLMSettings.instance
            .getExcludedExtensionsList()
            .map { ".$it".lowercase() }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying for LLM", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "Preparing copy operation..."

                try {
                    // Apply filters before building content
                    val toCopy = selectedFiles
                        .filter { file ->
                            !shouldExclude(file, gitignorePatterns, extraExts)
                                    && !FileTypeRegistry.getInstance().isFileIgnored(file)
                        }
                        .toTypedArray()

                    logger.info("Copying ${toCopy.size} files after filtering.")

                    val contentBuilder = LlmContentBuilder(project, indicator)
                    val result = contentBuilder.buildContent(toCopy)

                    indicator.fraction = 1.0
                    indicator.text = "Copying to clipboard..."

                    ApplicationManager.getApplication().invokeLater {
                        val contentToCopy = result.clipboardContent
                        CopyPasteManager.getInstance().setContents(StringSelection(contentToCopy))
                        showNotification(
                            project,
                            NotificationType.INFORMATION,
                            "Copied ${result.fileCount} files (${result.skippedCount} skipped, ${contentToCopy.length} characters) to clipboard."
                        )
                    }
                } catch (ex: Exception) {
                    logger.error("Copy for LLM Action failed.", ex)
                    ApplicationManager.getApplication().invokeLater {
                        showNotification(
                            project,
                            NotificationType.ERROR,
                            "Error copying content: ${ex.message ?: "Unknown error"}"
                        )
                    }
                }
            }
        })
    }

    private fun resolveVirtualFile(navigatable: Navigatable?): VirtualFile? {
        if (navigatable == null) return null
        // (same resolution logic as before…)
        // …
        return null
    }

    private fun showNotification(project: Project, type: NotificationType, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(notificationGroupId)
            .createNotification(content, type)
            .notify(project)
    }

    // ——— New from the “Plus” action ———

    private fun shouldExclude(
        file: VirtualFile,
        gitignorePatterns: List<Pattern>,
        extraExts: List<String>
    ): Boolean {
        val path = file.path
        if (gitignorePatterns.any { it.matcher(path).find() }) return true

        val name = file.name.lowercase()
        if (extraExts.any { name.endsWith(it) }) return true

        return false
    }

    private fun loadGitignorePatterns(project: Project): List<Pattern> {
        val root = project.baseDir
        val gitignoreFile = root.findChild(".gitignore") ?: return emptyList()
        val text = VfsUtilCore.loadText(gitignoreFile)
        return text.lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { globToRegex(it) }
            .map { Pattern.compile(it) }
    }

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        for (ch in glob) {
            when (ch) {
                '*'  -> sb.append(".*")
                '?'  -> sb.append('.')
                '.', '+', '(', ')', '|', '^', '$', '@', '%', '\\' ->
                    sb.append("\\").append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
