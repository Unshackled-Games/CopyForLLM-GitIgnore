package com.`unshackled-games`.copyforllmplus

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileTypes.FileTypeRegistry
import java.awt.datatransfer.StringSelection
import java.util.regex.Pattern

class CopyForLLMPlusAction : AnAction(
    "Copy for LLM Plus",
    "Copy all project files (excluding .gitignore & custom extensions) to clipboard",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val baseDir: VirtualFile = project.baseDir ?: return

        // 1) load .gitignore patterns
        val gitignorePatterns = loadGitignorePatterns(project)

        // 2) load user-configured extra extensions ([".log", ".tmp", ...])
        val extraExts = CopyForLLMSettings.instance
            .getExcludedExtensionsList()
            .map { ".$it" }

        // 3) walk & append
        val sb = StringBuilder()
        appendFiles(baseDir, baseDir, sb, gitignorePatterns, extraExts)

        // 4) copy to clipboard
        CopyPasteManager.getInstance()
            .setContents(StringSelection(sb.toString()))
    }

    override fun update(e: AnActionEvent) {
        // only enabled when a project is open
        e.presentation.isEnabledAndVisible = e.project?.baseDir != null
    }

    /**
     * Recursively walk [file], appending non-excluded files to [sb].
     */
    private fun appendFiles(
        file: VirtualFile,
        baseDir: VirtualFile,
        sb: StringBuilder,
        gitignorePatterns: List<Pattern>,
        extraExts: List<String>
    ) {
        if (file.isDirectory) {
            for (child in file.children) {
                appendFiles(child, baseDir, sb, gitignorePatterns, extraExts)
            }
        } else {
            if (shouldExclude(file, gitignorePatterns, extraExts)) return
            if (FileTypeRegistry.getInstance().isFileIgnored(file)) return

            val relPath = VfsUtilCore.getRelativePath(file, baseDir, '/')
            sb.append("// File: ").append(relPath).append("\n")
            sb.append(VfsUtilCore.loadText(file)).append("\n\n")
        }
    }

    private fun shouldExclude(
        file: VirtualFile,
        gitignorePatterns: List<Pattern>,
        extraExts: List<String>
    ): Boolean {
        // a) matches any .gitignore glob?
        val path = file.path
        if (gitignorePatterns.any { it.matcher(path).find() }) return true

        // b) has a user-excluded extension?
        val name = file.name.lowercase()
        if (extraExts.any { name.endsWith(it) }) return true

        return false
    }

    private fun loadGitignorePatterns(project: Project): List<Pattern> {
        val root = project.baseDir
        val gitignore = root.findChild(".gitignore") ?: return emptyList()
        val text = VfsUtilCore.loadText(gitignore)

        return text.lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { globToRegex(it) }
            .map { Pattern.compile(it) }
    }

    /** Convert a simple glob (with `*` and `?`) into a regex. */
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