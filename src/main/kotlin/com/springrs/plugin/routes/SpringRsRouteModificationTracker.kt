package com.springrs.plugin.routes

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
 */
@Service(Service.Level.PROJECT)
class SpringRsRouteModificationTracker(project: Project) : SimpleModificationTracker(), Disposable {

    init {
        val connection = project.messageBus.connect(this)

        // Listen to any PSI changes (including string literal changes) to catch `.route("/path", ...)` edits.
        // This also catches Rust structure changes, so we don't need to subscribe separately.
        connection.subscribe(ANY_PSI_CHANGE_TOPIC, object : AnyPsiChangeListener {
            override fun afterPsiChanged(isPhysical: Boolean) {
                incModificationCount()
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
