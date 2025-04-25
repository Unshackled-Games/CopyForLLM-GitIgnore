package com.aykoo.copyforllm

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.datatransfer.StringSelection


class CopyForLlmAction : AnAction() {

    private val logger = Logger.getInstance(CopyForLlmAction::class.java)
    private val notificationGroupId = "CopyForLLMNotifications"

    /**
     * Specify that the update method should run on the EDT (Event Dispatch Thread)
     * as it accesses data context which requires the UI thread. Required for newer platform versions.
     */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    /**
     * Determines if the action should be enabled and visible.
     * Enabled only if a project is open and at least one item is selected in the context.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val navigatables = e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)
        val isEnabled = project != null && !navigatables.isNullOrEmpty()
        // Logger call kept for temporary debugging if needed, can be removed later
        // logger.info("CopyForLlmAction update called. Project: ${project?.name}, Navigatables selected: ${navigatables?.size ?: 0}, Setting enabled: $isEnabled")
        e.presentation.isEnabledAndVisible = isEnabled
    }

    /**
     * Executes the action when the user clicks the menu item.
     * Resolves selected items to VirtualFiles and triggers the LlmContentBuilder in a background task.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val navigatables = e.getData(CommonDataKeys.NAVIGATABLE_ARRAY) ?: return

        // Optional: Log the types received for deeper debugging if resolution fails
        // navigatables.forEachIndexed { index, nav ->
        //    logger.info("Navigatable[$index]: Type=${nav?.javaClass?.name ?: "null"}, Value=${nav}")
        // }

        val selectedFiles = navigatables.mapNotNull { navigatable ->
            resolveVirtualFile(navigatable) // Use helper function for clarity
        }.distinct().toTypedArray()

        logger.info("Resolved ${selectedFiles.size} distinct VirtualFiles from ${navigatables.size} Navigatables.")
        // Optional: Log resolved files
        // selectedFiles.forEachIndexed { index, vf -> logger.debug("  Final VF[$index]: ${vf.path}") }

        if (selectedFiles.isEmpty()) {
            logger.warn("Action performed but failed to resolve any VirtualFiles from the selected Navigatables.")
            showNotification(
                project,
                NotificationType.WARNING,
                "Could not determine the selected files/folders to copy."
            )
            return
        }

        // Run the potentially long-running operation in a background thread
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying for LLM", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "Preparing copy operation..."

                try {
                    val contentBuilder = LlmContentBuilder(project, indicator)
                    val result = contentBuilder.buildContent(selectedFiles)

                    indicator.fraction = 1.0
                    indicator.text = "Copying to clipboard..."

                    // Copying to clipboard must happen on the EDT
                    ApplicationManager.getApplication().invokeLater {
                        val contentToCopy = result.clipboardContent
                        val charCount = contentToCopy.length

                        CopyPasteManager.getInstance().setContents(StringSelection(contentToCopy))
                        showNotification(
                            project,
                            NotificationType.INFORMATION,
                            "Copied ${result.fileCount} files (${result.skippedCount} skipped, ${charCount} characters) to clipboard."
                        )
                    }
                } catch (ex: Exception) { // Catch generic exceptions during the process
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

    /**
     * Attempts to resolve a VirtualFile from various common Navigatable types found in IDE contexts.
     */
    private fun resolveVirtualFile(navigatable: Navigatable?): VirtualFile? {
        if (navigatable == null) return null

        // --- Generic VirtualFile Resolution Logic ---
        var virtualFile: VirtualFile? = null

        // 1. Check if it IS a VirtualFile already
        if (navigatable is VirtualFile) {
            virtualFile = navigatable
        }

        // 2. Check if it's a PsiElement
        if (virtualFile == null && navigatable is PsiElement) {
            virtualFile = when (navigatable) {
                is PsiFile -> navigatable.virtualFile
                is PsiDirectory -> navigatable.virtualFile
                else -> navigatable.containingFile?.virtualFile // Fallback for other elements
            }
        }

        // 3. Check common Project View Node types (interface/base class)
        if (virtualFile == null && navigatable is ProjectViewNode<*>) {
            virtualFile = navigatable.virtualFile
        }

        // 4. Check AbstractTreeNode types (common base for tree nodes)
        if (virtualFile == null && navigatable is AbstractTreeNode<*>) {
            when (val value = navigatable.value) {
                is PsiElement -> virtualFile = when (value) {
                    is PsiFile -> value.virtualFile
                    is PsiDirectory -> value.virtualFile
                    else -> value.containingFile?.virtualFile
                }

                is VirtualFile -> virtualFile = value
            }
        }

        if (virtualFile == null) {
            logger.warn("Could not resolve VirtualFile for Navigatable type: ${navigatable.javaClass.name}")
        }

        return virtualFile
    }


    /** Displays a balloon notification in the IDE. */
    private fun showNotification(project: Project, type: NotificationType, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(notificationGroupId)
            .createNotification(content, type)
            .notify(project)
    }
}