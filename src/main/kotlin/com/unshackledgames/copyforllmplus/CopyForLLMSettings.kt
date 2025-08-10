package com.unshackledgames.copyforllmplus

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "CopyForLLMSettings",
    storages = [Storage("copyForLLM.xml")]
)
class CopyForLLMSettings : PersistentStateComponent<CopyForLLMSettings.State> {

    data class State(
        /** false => EXCLUDE mode (default), true => INCLUDE mode */
        var includeMode: Boolean = false,
        /** Example entries: "cs", ".kt", "*.md" */
        var includePatterns: MutableList<String> = mutableListOf(),
        /** Example entries: "png", "*.meta" */
        var excludePatterns: MutableList<String> = mutableListOf()
    )

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    // ---- Back-compat for older code that referenced CopyForLLMSettings.instance
    companion object {
        val instance: CopyForLLMSettings get() = service()
    }

    fun isIncludeMode(): Boolean = myState.includeMode

    fun normalizedInclude(): List<String> = myState.includePatterns
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

    fun normalizedExclude(): List<String> = myState.excludePatterns
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

    /**
     * Convert simple entries ("cs", ".kt", "*.md", "?akefile") to case-insensitive Regex.
     * Bare "cs" ⇒ "*.cs", ".kt" ⇒ "*.kt". Supports '*' and '?' wildcards.
     */
    fun toRegexes(patterns: List<String>): List<Regex> {
        fun toGlobRegex(glob: String): Regex {
            var g = glob.trim()
            if (g.isEmpty()) return Regex("$^") // match nothing

            if (!g.contains('*') && !g.contains('?')) {
                g = if (g.startsWith(".")) "*$g" else "*.$g"
            }

            val sb = StringBuilder()
            for (ch in g) {
                sb.append(
                    when (ch) {
                        '*' -> ".*"
                        '?' -> "."
                        '.', '(', ')', '+', '{', '}', '[', ']', '^', '$', '\\', '|' -> "\\$ch"
                        else -> ch
                    }
                )
            }
            return Regex("^$sb$", RegexOption.IGNORE_CASE)
        }
        return patterns.map(::toGlobRegex)
    }

    fun matchesAny(name: String, regexes: List<Regex>): Boolean =
        regexes.any { it.matches(name) }
}
