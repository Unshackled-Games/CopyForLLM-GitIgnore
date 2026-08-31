package com.unshackledgames.copyforllmplus

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection

class CopyForLLMAction : DumbAwareAction(
    "Copy for LLM",
    "Collect selected files and copy their contents for LLM use",
    null
) {
    private val logger = Logger.getInstance(CopyForLLMAction::class.java)

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

        val result = ReadAction.compute<CopyBuildResult, RuntimeException> {
            buildClipboardContent(project.basePath ?: "", filtered)
        }

        if (result.copiedCount == 0) {
            notify(
                project,
                "No text files could be copied. ${formatSkipped(result)}",
                NotificationType.WARNING
            )
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(result.text))

        val skipped = if (result.skippedCount > 0) " ${formatSkipped(result)}" else ""
        notify(
            project,
            "Copied ${result.copiedCount} file(s) to clipboard.$skipped",
            NotificationType.INFORMATION
        )
    }

    private fun buildClipboardContent(base: String, files: List<VirtualFile>): CopyBuildResult {
        val output = StringBuilder()
        var copiedCount = 0
        var binarySkipped = 0
        var oversizedSkipped = 0
        var unreadableSkipped = 0
        var budgetSkipped = 0

        files.forEach { file ->
            try {
                if (!file.isValid) {
                    unreadableSkipped++
                    return@forEach
                }

                val fileType = file.fileType
                if (fileType.isBinary && fileType !== UnknownFileType.INSTANCE) {
                    binarySkipped++
                    return@forEach
                }

                if (file.length > MAX_FILE_BYTES) {
                    oversizedSkipped++
                    return@forEach
                }

                val content = VfsUtilCore.loadText(file)
                if (looksBinary(content)) {
                    binarySkipped++
                    return@forEach
                }

                val header = "===== ${relPath(base, file.path)} =====\n"
                val requiredChars = header.length.toLong() + content.length.toLong() + 2L
                val remainingChars = MAX_CLIPBOARD_CHARS.toLong() - output.length.toLong()
                if (requiredChars > remainingChars) {
                    budgetSkipped++
                    return@forEach
                }

                output.append(header)
                output.append(content)
                output.append('\n')
                output.append('\n')
                copiedCount++
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                unreadableSkipped++
                logger.warn("Skipping unreadable file while copying for LLM: ${file.path}", e)
            }
        }

        return CopyBuildResult(
            text = output.toString(),
            copiedCount = copiedCount,
            binarySkipped = binarySkipped,
            oversizedSkipped = oversizedSkipped,
            unreadableSkipped = unreadableSkipped,
            budgetSkipped = budgetSkipped
        )
    }

    private fun looksBinary(content: CharSequence): Boolean {
        val sampleLength = minOf(content.length, BINARY_SAMPLE_CHARS)
        if (sampleLength == 0) return false

        var suspiciousChars = 0
        for (index in 0 until sampleLength) {
            val ch = content[index]
            if (ch == '\u0000') return true

            if (
                ch == '\uFFFD' ||
                (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
            ) {
                suspiciousChars++
            }
        }

        return suspiciousChars * 20 >= sampleLength
    }

    private fun formatSkipped(result: CopyBuildResult): String {
        val reasons = buildList {
            if (result.binarySkipped > 0) add("${result.binarySkipped} binary")
            if (result.oversizedSkipped > 0) add("${result.oversizedSkipped} over ${MAX_FILE_BYTES / MIB} MiB")
            if (result.unreadableSkipped > 0) add("${result.unreadableSkipped} unreadable")
            if (result.budgetSkipped > 0) add("${result.budgetSkipped} over the ${MAX_CLIPBOARD_CHARS / MIB}-MiB character budget")
        }
        return "Skipped ${result.skippedCount} file(s) (${reasons.joinToString()})."
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

    private data class CopyBuildResult(
        val text: String,
        val copiedCount: Int,
        val binarySkipped: Int,
        val oversizedSkipped: Int,
        val unreadableSkipped: Int,
        val budgetSkipped: Int
    ) {
        val skippedCount: Int
            get() = binarySkipped + oversizedSkipped + unreadableSkipped + budgetSkipped
    }

    companion object {
        private const val MIB = 1024 * 1024
        private const val MAX_FILE_BYTES = 4L * MIB
        private const val MAX_CLIPBOARD_CHARS = 8 * MIB
        private const val BINARY_SAMPLE_CHARS = 8192
    }
}
