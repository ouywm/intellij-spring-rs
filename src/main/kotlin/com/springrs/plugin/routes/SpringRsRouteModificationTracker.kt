package com.springrs.plugin.routes

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.impl.AnyPsiChangeListener
import com.intellij.psi.impl.PsiManagerImpl.ANY_PSI_CHANGE_TOPIC

/**
 * Route-specific modification tracker.
 *
 * This tracker listens to PSI changes and increments a counter to invalidate route caches.
 *
 * We use ANY_PSI_CHANGE_TOPIC to catch all kinds of changes, including:
 * - function attribute changes (e.g. #[get("/path")])
 * - path string changes inside `.route()` calls
 * - prefix string changes inside `.nest()` calls
 * - module-level `#[nest]` attribute changes
 *
 * Note: this tracker is required because `rustStructureModificationTracker` only tracks structural changes
 * and won't increment on string literal changes inside function bodies.
 *
 * IMPORTANT: We skip non-physical PSI changes and changes during indexing (dumb mode)
 * to avoid unnecessary cache invalidation that causes repeated scanning/indexing loops.
 */
@Service(Service.Level.PROJECT)
class SpringRsRouteModificationTracker(project: Project) : SimpleModificationTracker(), Disposable {

    init {
        val connection = project.messageBus.connect(this)

        connection.subscribe(ANY_PSI_CHANGE_TOPIC, object : AnyPsiChangeListener {
            override fun afterPsiChanged(isPhysical: Boolean) {
                // Only invalidate on physical (real file) changes, not synthetic/indexing PSI events.
                // Also skip during dumb mode (indexing) to prevent cache rebuild loops.
                if (isPhysical && !DumbService.isDumb(project)) {
                    incModificationCount()
                }
            }
        })
    }

    override fun dispose() {
        // Nothing to do: MessageBusConnection disconnects automatically.
    }

    companion object {
        fun getInstance(project: Project): SpringRsRouteModificationTracker = project.service()
    }
}
