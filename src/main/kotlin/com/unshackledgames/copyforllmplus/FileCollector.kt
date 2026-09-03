package com.unshackledgames.copyforllmplus

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object FileCollector {

    /** Top-level entry: gather files from selection/editor, or fall back to entire project dir. */
    fun collectAllCandidateFiles(project: Project, dataContext: DataContext): List<VirtualFile> {
        val selection = collectSelected(dataContext)
        if (selection.isNotEmpty()) return selection

        // Fallback: selected editor files
        val fromEditors = FileEditorManager.getInstance(project).selectedFiles.toList()
        if (fromEditors.isNotEmpty()) return expand(fromEditors)

        // Last fallback: whole project content root
        val basePath = project.basePath
        val baseVf = basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        if (baseVf != null && baseVf.isValid) {
            return expand(listOf(baseVf))
        }

        return emptyList()
    }

    /** Get VirtualFiles from Project View selection (or empty). */
    private fun collectSelected(dataContext: DataContext): List<VirtualFile> {
        val arr = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext) ?: return emptyList()
        return expand(arr.toList())
    }

    /** Expand directories recursively, skip invalids/dirs in final list, dedupe. */
    private fun expand(files: List<VirtualFile>): List<VirtualFile> {
        val out = LinkedHashSet<VirtualFile>()
        files.forEach { vf ->
            if (!vf.isValid) return@forEach
            if (vf.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(
                    vf,
                    { file -> file != null && file.isValid },
                    { child ->
                        if (child != null && child.isValid && !child.isDirectory) {
                            out.add(child)
                        }
                        true
                    }
                )
            } else {
                out.add(vf)
            }
        }
        return out.toList()
    }

    /** Apply include/exclude logic from settings. */
    fun filterBySettings(settings: CopyForLLMSettings, files: List<VirtualFile>): List<VirtualFile> {
        val includeMode = settings.isIncludeMode()
        val includePatterns = settings.normalizedInclude()
        val excludePatterns = settings.normalizedExclude()
        val includeNoExtension = includePatterns.hasNoExtensionToken()
        val excludeNoExtension = excludePatterns.hasNoExtensionToken()
        val inc = settings.toRegexes(includePatterns.withoutNoExtensionToken())
        val exc = settings.toRegexes(excludePatterns.withoutNoExtensionToken())

        // Be generous: empty include list in include mode => include all (then apply exclude).
        // A lone "(no extension)" token is not an empty include list.
        val includeAllIfEmpty = includeMode && inc.isEmpty() && !includeNoExtension

        return files.filter { vf ->
            if (!vf.isValid || vf.isDirectory) return@filter false

            val hasNoExtension = vf.extension.isNullOrEmpty()
            val name = vf.name
            val inInclude = if (includeAllIfEmpty) {
                true
            } else {
                (includeNoExtension && hasNoExtension) || settings.matchesAny(name, inc)
            }
            val inExclude =
                (excludeNoExtension && hasNoExtension) || settings.matchesAny(name, exc)

            if (includeMode) (inInclude && !inExclude) else (!inExclude)
        }
    }

    private fun List<String>.hasNoExtensionToken(): Boolean =
        any { it.equals(NO_EXTENSION_TOKEN, ignoreCase = true) }

    private fun List<String>.withoutNoExtensionToken(): List<String> =
        filterNot { it.equals(NO_EXTENSION_TOKEN, ignoreCase = true) }

    private const val NO_EXTENSION_TOKEN = "(no extension)"
}
