package com.unshackledgames.copyforllmplus

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

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

        val basePath = project.basePath ?: ""
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying for LLM", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val bundle = buildBundle(basePath, filtered, indicator)
                    val payload = createClipboardPayload(bundle)

                    ApplicationManager.getApplication().invokeLater {
                        CopyPasteManager.getInstance().setContents(payload.transferable)
                        payload.afterCopied()
                        notify(project, formatSuccess(bundle, payload), NotificationType.INFORMATION)
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Copy for LLM failed.", e)
                    ApplicationManager.getApplication().invokeLater {
                        notify(
                            project,
                            "Could not build the copy bundle: ${e.message ?: e.javaClass.simpleName}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        })
    }

    private fun buildBundle(
        basePath: String,
        files: List<VirtualFile>,
        indicator: ProgressIndicator
    ): BundleResult {
        val bundlePath = Files.createTempFile("copyforllm-", ".txt")
        bundlePath.toFile().deleteOnExit()

        var binaryCount = 0
        var errorCount = 0
        val largestSourceTypes = findLargestSourceTypes(files)

        try {
            Files.newBufferedWriter(bundlePath, StandardCharsets.UTF_8).use { writer ->
                files.forEachIndexed { index, file ->
                    indicator.checkCanceled()
                    indicator.isIndeterminate = false
                    indicator.fraction = index.toDouble() / files.size.toDouble()
                    indicator.text2 = "Processing ${index + 1}/${files.size}: ${file.name}"

                    val result = writeFile(basePath, file, writer, indicator)
                    if (result == FileWriteResult.BINARY) binaryCount++
                    if (result == FileWriteResult.ERROR) errorCount++
                }
            }

            indicator.fraction = 1.0
            indicator.text2 = "Preparing clipboard..."

            return BundleResult(
                path = bundlePath,
                fileCount = files.size,
                binaryCount = binaryCount,
                errorCount = errorCount,
                byteCount = Files.size(bundlePath),
                largestSourceTypes = largestSourceTypes
            )
        } catch (e: Exception) {
            Files.deleteIfExists(bundlePath)
            throw e
        }
    }

    private fun findLargestSourceTypes(files: List<VirtualFile>): List<SourceTypeStats> {
        val totals = HashMap<String, MutableSourceTypeStats>()

        for (file in files) {
            if (!file.isValid) continue

            val label = file.extension
                ?.takeIf { it.isNotBlank() }
                ?.lowercase()
                ?.let { ".$it" }
                ?: "(no extension)"
            val stats = totals.getOrPut(label) { MutableSourceTypeStats() }
            stats.byteCount += file.length.coerceAtLeast(0L)
            stats.fileCount++
        }

        return totals.entries
            .sortedByDescending { it.value.byteCount }
            .take(MAX_LARGEST_SOURCE_TYPES)
            .map { (label, stats) ->
                SourceTypeStats(label, stats.byteCount, stats.fileCount)
            }
    }

    private fun writeFile(
        basePath: String,
        file: VirtualFile,
        writer: BufferedWriter,
        indicator: ProgressIndicator
    ): FileWriteResult {
        writer.append("===== ")
        writer.append(relPath(basePath, file.path))
        writer.appendLine(" =====")

        if (!file.isValid) {
            writer.appendLine("[file became unavailable before it could be copied]")
            writer.newLine()
            return FileWriteResult.ERROR
        }

        return try {
            if (isBinary(file)) {
                writer.appendLine("[binary file; Base64]")
                writeBase64(file, writer, indicator)
                writer.newLine()
                writer.newLine()
                FileWriteResult.BINARY
            } else {
                writeText(file, writer, indicator)
                writer.newLine()
                writer.newLine()
                FileWriteResult.TEXT
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not read file while copying for LLM: ${file.path}", e)
            writer.newLine()
            writer.append("[error reading file: ")
            writer.append(e.message ?: e.javaClass.simpleName)
            writer.appendLine("]")
            writer.newLine()
            FileWriteResult.ERROR
        }
    }

    private fun isBinary(file: VirtualFile): Boolean {
        val fileType = file.fileType
        if (fileType.isBinary && fileType !== UnknownFileType.INSTANCE) return true
        if (fileType !== UnknownFileType.INSTANCE) return false

        file.inputStream.use { input ->
            val sample = ByteArray(BINARY_SAMPLE_BYTES)
            val count = input.read(sample)
            if (count <= 0) return false

            var suspiciousBytes = 0
            for (index in 0 until count) {
                val value = sample[index].toInt() and 0xFF
                if (value == 0) return true
                if (
                    value in 0x01..0x08 ||
                    value in 0x0B..0x0C ||
                    value in 0x0E..0x1F ||
                    value == 0x7F
                ) {
                    suspiciousBytes++
                }
            }

            return suspiciousBytes * 20 >= count
        }
    }

    private fun writeText(file: VirtualFile, writer: BufferedWriter, indicator: ProgressIndicator) {
        InputStreamReader(file.inputStream, file.charset).use { reader ->
            val buffer = CharArray(TEXT_BUFFER_CHARS)
            while (true) {
                indicator.checkCanceled()
                val read = reader.read(buffer)
                if (read < 0) return
                writer.write(buffer, 0, read)
            }
        }
    }

    private fun writeBase64(file: VirtualFile, writer: BufferedWriter, indicator: ProgressIndicator) {
        file.inputStream.use { input ->
            val buffer = ByteArray(BASE64_CHUNK_BYTES)
            while (true) {
                indicator.checkCanceled()
                val read = input.read(buffer)
                if (read < 0) return
                if (read == 0) continue

                writer.appendLine(Base64.getEncoder().encodeToString(buffer.copyOf(read)))
            }
        }
    }

    private fun createClipboardPayload(bundle: BundleResult): ClipboardPayload {
        if (bundle.byteCount <= INLINE_CLIPBOARD_BYTES) {
            val text = Files.readString(bundle.path, StandardCharsets.UTF_8)
            return ClipboardPayload(
                transferable = StringSelection(text),
                mode = ClipboardMode.TEXT,
                afterCopied = { Files.deleteIfExists(bundle.path) }
            )
        }

        return ClipboardPayload(
            transferable = FileTransferable(bundle.path.toFile()),
            mode = ClipboardMode.FILE,
            afterCopied = {}
        )
    }

    private fun formatSuccess(bundle: BundleResult, payload: ClipboardPayload): String {
        val binary = if (bundle.binaryCount > 0) {
            " ${bundle.binaryCount} binary file(s) were included as Base64."
        } else {
            ""
        }
        val errors = if (bundle.errorCount > 0) {
            " ${bundle.errorCount} unreadable file(s) are represented by error markers."
        } else {
            ""
        }
        val largestTypes = if (bundle.largestSourceTypes.isNotEmpty()) {
            " Largest source types: " + bundle.largestSourceTypes.joinToString(", ") { stats ->
                "${stats.label} ${formatSize(stats.byteCount)} (${stats.fileCount})"
            }
        } else {
            ""
        }

        return when (payload.mode) {
            ClipboardMode.TEXT ->
                "Copied all ${bundle.fileCount} file(s) to the clipboard.$binary$errors$largestTypes"

            ClipboardMode.FILE ->
                "Copied all ${bundle.fileCount} file(s) as a ${formatMiB(bundle.byteCount)} MiB bundle file. " +
                    "Paste or attach the file in the destination.$binary$errors$largestTypes"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= MIB -> "${formatMiB(bytes)} MiB"
        bytes >= KIB -> String.format("%.1f KiB", bytes.toDouble() / KIB.toDouble())
        else -> "$bytes B"
    }

    private fun formatMiB(bytes: Long): String =
        String.format("%.1f", bytes.toDouble() / MIB.toDouble())

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

    private data class BundleResult(
        val path: Path,
        val fileCount: Int,
        val binaryCount: Int,
        val errorCount: Int,
        val byteCount: Long,
        val largestSourceTypes: List<SourceTypeStats>
    )

    private data class SourceTypeStats(
        val label: String,
        val byteCount: Long,
        val fileCount: Int
    )

    private class MutableSourceTypeStats(
        var byteCount: Long = 0,
        var fileCount: Int = 0
    )

    private data class ClipboardPayload(
        val transferable: Transferable,
        val mode: ClipboardMode,
        val afterCopied: () -> Unit
    )

    private enum class ClipboardMode {
        TEXT,
        FILE
    }

    private enum class FileWriteResult {
        TEXT,
        BINARY,
        ERROR
    }

    private class FileTransferable(private val file: File) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return listOf(file)
        }
    }

    companion object {
        private const val KIB = 1024L
        private const val MIB = 1024L * KIB
        private const val INLINE_CLIPBOARD_BYTES = 16L * MIB
        private const val BINARY_SAMPLE_BYTES = 8192
        private const val TEXT_BUFFER_CHARS = 8192
        private const val BASE64_CHUNK_BYTES = 24 * 1024
        private const val MAX_LARGEST_SOURCE_TYPES = 10
    }
}
