package com.springrs.plugin.lifecycle

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * A project-level Disposable to use as a parent for async tasks/subscriptions.
 *
 * IntelliJ inspections discourage using [com.intellij.openapi.project.Project] itself as a Disposable
 * (e.g. `.expireWith(project)`). Use this service instead.
 */
@Service(Service.Level.PROJECT)
class SpringRsProjectLifetimeDisposable : Disposable {
    override fun dispose() {
        // no-op
    }
}

