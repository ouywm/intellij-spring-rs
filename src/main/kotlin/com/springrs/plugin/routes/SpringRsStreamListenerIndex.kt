package com.springrs.plugin.routes

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.utils.FilePathValidator
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction

/**
 * Collects spring-rs stream listener definitions from Rust source code.
 *
 * Scans for functions annotated with:
 * - #[stream_listener("topic")]
 * - #[stream_listener("topic", kafka_consumer_options = fn)]
 * - etc.
 *
 * Used by the tool window and gutter icons.
 */
object SpringRsStreamListenerIndex {

    data class StreamListener(
        val topics: List<String>,
        val handlerName: String?,
        val file: VirtualFile,
        val offset: Int,
        val consumerMode: String? = null,
        val groupId: String? = null,
        val optionsType: String? = null
    ) {
        /** Primary topic for display. */
        val primaryTopic: String get() = topics.firstOrNull() ?: "?"

        /** All topics as display string. */
        fun topicsDisplay(): String = topics.joinToString(", ")
    }

    private val LISTENERS_KEY: Key<CachedValue<List<StreamListener>>> =
        Key.create("com.springrs.plugin.routes.SpringRsStreamListenerIndex.LISTENERS")

    fun getListenersCached(project: Project): List<StreamListener> {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            LISTENERS_KEY,
            {
                val listeners = buildListeners(project)
                CachedValueProvider.Result.create(
                    listeners,
                    SpringRsRouteModificationTracker.getInstance(project)
                )
            },
            false
        )
    }

    private fun buildListeners(project: Project): List<StreamListener> {
        val listeners = mutableListOf<StreamListener>()
        val scope = GlobalSearchScope.projectScope(project)

        for (vFile in FileTypeIndex.getFiles(RsFileType, scope)) {
            ProgressManager.checkCanceled()
            if (FilePathValidator.isMacroExpanded(vFile.path)) continue

            val rsFile = PsiManager.getInstance(project).findFile(vFile) as? RsFile ?: continue

            for (fn in PsiTreeUtil.findChildrenOfType(rsFile, RsFunction::class.java)) {
                ProgressManager.checkCanceled()
                val info = SpringRsStreamListenerUtil.extractStreamListenerInfo(fn) ?: continue
                val fnName = fn.name
                val offset = fn.identifier?.textOffset ?: fn.textOffset

                listeners.add(
                    StreamListener(
                        topics = info.topics,
                        handlerName = fnName,
                        file = vFile,
                        offset = offset,
                        consumerMode = info.consumerMode,
                        groupId = info.groupId,
                        optionsType = info.optionsType
                    )
                )
            }
        }

        return listeners
            .distinctBy { "${it.topics} ${it.handlerName} ${it.file.path}:${it.offset}" }
            .sortedWith(compareBy<StreamListener> { it.primaryTopic }.thenBy { it.handlerName ?: "" })
    }
}
