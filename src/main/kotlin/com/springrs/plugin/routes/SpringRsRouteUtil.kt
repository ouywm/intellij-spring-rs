package com.springrs.plugin.routes

import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.compat.PsiCompat
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue

/**
 * Utilities for extracting HTTP routes from Rust PSI (spring-rs / axum style).
 *
 * Supported patterns:
 * - Attribute macros on functions (spring_web): #[get("/x")], #[post("/x")], #[route("/x", method="GET", ...)]
 * - Module nesting prefix (spring_web): #[nest("/prefix")] mod api { ... }
 * - Router builder calls (axum):
 *   - Router::new().route("/x", get(handler)) (and method-router chains)
 *   - Router::new().route("/x", any(handler)) (handle all HTTP methods)
 *   - Router::new().route("/x", on(MethodFilter::POST, handler)) (using MethodFilter)
 *   - Router::new().route("/x", get_service(svc)) (service-based routing)
 *   - Router::new().route("/x", any_service(svc)) (service for all methods)
 *   - Router::new().route("/x", on_service(MethodFilter::POST, svc)) (service with MethodFilter)
 *   - Router::new().nest("/api", inner_router) (nested routers with prefix)
 *   - Router::new().merge(other_router) (merged routers)
 */

// TODO: Scan routes in dependencies. Skip crates that don't depend on axum or other web frameworks.
object SpringRsRouteUtil {

    data class RouteInfo(
        val method: String,
        val path: String,
        val handlerName: String? = null
    )

    /**
     * Like [RouteInfo] but keeps the handler expression so the caller can resolve/navigate to it.
     *
     * NOTE: Do not cache instances of this class long-term (it holds PSI).
     */
    data class RouteTarget(
        val method: String,
        val path: String,
        val handlerExpr: RsExpr?,
        val handlerName: String?
    )

    /**
     * Route parameter info (axum style).
     *
     * axum supports two route parameter formats:
     * - capture: /{key} - matches a single path segment
     * - wildcard: /{*key} - matches the rest of the path
     *
     * @param name parameter name (e.g. "id", "path")
     * @param isWildcard whether it is a wildcard parameter (e.g. {*path})
     * @param originalSegment original segment text (e.g. "{id}", "{*path}")
     */
    data class RouteParam(
        val name: String,
        val isWildcard: Boolean,
        val originalSegment: String
    )

    /**
     * Extracts route parameters from a route path (axum style).
     *
     * Supported formats:
     * - {id} - capture parameter that matches a single path segment
     * - {*path} - wildcard parameter that matches all remaining segments
     *
     * @param path route path, e.g. "/users/{id}/files/{*rest}"
     * @return parameter list
     */
    fun extractRouteParams(path: String): List<RouteParam> {
        val params = mutableListOf<RouteParam>()

        // Match `{name}` or `{*name}` (axum style).
        val pattern = Regex("\\{(\\*?)([^}]+)}")
        pattern.findAll(path).forEach { match ->
            val isWildcard = match.groupValues[1] == "*"
            val name = match.groupValues[2]
            params.add(RouteParam(name, isWildcard, match.value))
        }

        return params
    }

    /**
     * Formats route parameters for display.
     *
     * @param params parameter list
     * @return formatted string, e.g. "id, *path"
     */
    fun formatRouteParams(params: List<RouteParam>): String {
        if (params.isEmpty()) return ""
        return params.joinToString(", ") { param ->
            if (param.isWildcard) "*${param.name}" else param.name
        }
    }

    private val METHOD_ATTRS: Map<String, String> = mapOf(
        "get" to "GET",
        "post" to "POST",
        "put" to "PUT",
        "delete" to "DELETE",
        "patch" to "PATCH",
        "head" to "HEAD",
        "options" to "OPTIONS",
        "trace" to "TRACE",
        "connect" to "CONNECT",
        // spring_web api macros (OpenAPI)
        "get_api" to "GET",
        "post_api" to "POST",
        "put_api" to "PUT",
        "delete_api" to "DELETE",
        "patch_api" to "PATCH",
        "head_api" to "HEAD",
        "options_api" to "OPTIONS",
        "trace_api" to "TRACE"
    )

    /**
     * axum *_service function mapping, e.g. get_service/post_service/any_service.
     */
    private val SERVICE_METHOD_ATTRS: Map<String, String> = mapOf(
        "get_service" to "GET",
        "post_service" to "POST",
        "put_service" to "PUT",
        "delete_service" to "DELETE",
        "patch_service" to "PATCH",
        "head_service" to "HEAD",
        "options_service" to "OPTIONS",
        "trace_service" to "TRACE",
        "connect_service" to "CONNECT",
        "any_service" to "ANY"
    )

    /**
     * MethodFilter enum mapping.
     */
    private val METHOD_FILTER_MAP: Map<String, String> = mapOf(
        "GET" to "GET",
        "POST" to "POST",
        "PUT" to "PUT",
        "DELETE" to "DELETE",
        "PATCH" to "PATCH",
        "HEAD" to "HEAD",
        "OPTIONS" to "OPTIONS",
        "TRACE" to "TRACE",
        "CONNECT" to "CONNECT"
    )

    /**
     * Extract attribute-based routes from a function definition.
     */
    fun extractAttributeRoutes(fn: RsFunction): List<RouteInfo> {
        val routes = mutableListOf<RouteInfo>()

        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            val name = meta.name ?: continue

            // Handle #[route(...)] and #[api_route(...)] macros
            if (name == "route" || name == "api_route") {
                val path = extractFirstStringArg(meta) ?: continue
                val methods = extractRouteMethods(meta)
                if (methods.isEmpty()) continue
                for (m in methods) {
                    routes.add(RouteInfo(method = m, path = path))
                }
                continue
            }

            val method = METHOD_ATTRS[name] ?: continue
            val path = extractFirstStringArg(meta) ?: continue
            routes.add(RouteInfo(method = method, path = path))
        }

        return routes
    }

    /**
     * Computes a nesting prefix for a function by walking parent modules and collecting #[nest("/...")] attributes.
     */
    fun computeNestPrefix(fn: RsFunction): String {
        val segments = mutableListOf<String>()
        var mod: RsModItem? = PsiTreeUtil.getParentOfType(fn, RsModItem::class.java)
        while (mod != null) {
            val nest = extractNestPath(mod)
            if (nest != null) segments.add(nest)
            mod = PsiTreeUtil.getParentOfType(mod, RsModItem::class.java)
        }

        var prefix = ""
        for (segment in segments.asReversed()) {
            prefix = joinPaths(prefix, segment)
        }
        return prefix
    }

    /**
     * Joins two URL path segments with normalization.
     */
    fun joinPaths(prefix: String?, path: String?): String {
        val a = (prefix ?: "").trim()
        val b = (path ?: "").trim()
        if (a.isEmpty()) return normalizePath(b)
        if (b.isEmpty()) return normalizePath(a)

        val left = if (a.endsWith("/")) a.dropLast(1) else a
        val right = if (b.startsWith("/")) b else "/$b"
        return normalizePath(left + right)
    }

    private fun normalizePath(p: String): String {
        var s = p.trim()
        if (s.isEmpty()) return "/"
        if (!s.startsWith("/")) s = "/$s"
        s = s.replace(Regex("/+"), "/")
        // Strip trailing slashes (but keep the root path "/").
        if (s.length > 1 && s.endsWith("/")) {
            s = s.dropLast(1)
        }
        return s
    }

    /**
     * Extract routes from a Router builder call, e.g. `.route("/x", get(handler))`.
     *
     * Also supports method-router chains like `get(a).post(b)`.
     */
    fun extractRouterCallRoutes(call: RsMethodCall): List<RouteInfo> {
        return extractRouterCallRouteTargets(call).map { t ->
            RouteInfo(method = t.method, path = t.path, handlerName = t.handlerName)
        }
    }

    /**
     * Same as [extractRouterCallRoutes], but also returns the handler expression for navigation.
     */
    fun extractRouterCallRouteTargets(call: RsMethodCall): List<RouteTarget> {
        if (call.referenceName != "route") return emptyList()

        val args = call.valueArgumentList.exprList
        if (args.size < 2) return emptyList()

        val path = (args[0] as? RsLitExpr)?.stringValue ?: return emptyList()
        val routers = extractMethodRouterTargets(args[1])
        if (routers.isEmpty()) return emptyList()

        return routers.map { r ->
            RouteTarget(method = r.method, path = path, handlerExpr = r.handlerExpr, handlerName = r.handlerName)
        }
    }

    private fun extractFirstStringArg(meta: RsMetaItem): String? {
        val args = meta.metaItemArgs ?: return null

        // Most attribute macros are written like: #[get("/x")]. In Rust PSI, the literal often appears in
        // `litExprList` (not necessarily in `metaItemList`), so handle both.
        args.litExprList.firstOrNull()?.stringValue?.let { return it }

        val metaItems = args.metaItemList

        // Prefer the unnamed first argument: #[get("/x")]
        val unnamed = metaItems.firstOrNull { it.name.isNullOrBlank() } ?: metaItems.firstOrNull()
        unnamed?.litExpr?.stringValue?.let { return it }

        // Named style fallback: #[route(path = "/x", method = "GET")]
        metaItems.firstOrNull { it.name == "path" }?.litExpr?.stringValue?.let { return it }

        return null
    }

    private fun extractRouteMethods(meta: RsMetaItem): List<String> {
        val args = meta.metaItemArgs?.metaItemList ?: return emptyList()
        val methods = args
            .filter { it.name == "method" }
            .mapNotNull { it.litExpr?.stringValue }
            .map { it.uppercase() }
        return methods.distinct()
    }

    private data class MethodRouterTarget(
        val method: String,
        val handlerExpr: RsExpr?,
        val handlerName: String?
    )

    private fun extractMethodRouterTargets(expr: RsExpr): List<MethodRouterTarget> {
        return when {
            expr is RsCallExpr -> {
                extractMethodRouterFromCallExpr(expr)
            }
            PsiCompat.isMethodCallExpr(expr) -> {
                // Method-router chain: get(a).post(b) or any_service(svc).post_service(other_svc)
                val receiver = PsiCompat.getMethodCallReceiver(expr)
                val base = if (receiver != null) extractMethodRouterTargets(receiver) else emptyList()
                val methodName = PsiCompat.getMethodCallName(expr)
                val args = PsiCompat.getMethodCallArgs(expr)

                // Check for `.on(MethodFilter::XXX, handler)` / `.on_service(MethodFilter::XXX, svc)`.
                if (methodName == "on" || methodName == "on_service") {
                    if (args.size >= 2) {
                        val method = extractMethodFilterFromExpr(args[0])
                        val handlerExpr = args[1]
                        val handlerName = extractHandlerName(handlerExpr)
                        if (method != null) {
                            return base + MethodRouterTarget(method, handlerExpr, handlerName)
                        }
                    }
                    return base
                }

                // Standard methods: get/post/put/delete/...
                val method = METHOD_ATTRS[methodName]
                if (method != null) {
                    val argList = PsiCompat.getMethodCallValueArgumentList(expr)
                    val handlerExpr = extractFirstArg(argList)
                    val handlerName = extractHandlerName(handlerExpr)
                    return base + MethodRouterTarget(method, handlerExpr, handlerName)
                }

                // *_service methods: get_service/post_service/any_service/...
                val serviceMethod = SERVICE_METHOD_ATTRS[methodName]
                if (serviceMethod != null) {
                    val argList = PsiCompat.getMethodCallValueArgumentList(expr)
                    val handlerExpr = extractFirstArg(argList)
                    val handlerName = extractHandlerName(handlerExpr)
                    return base + MethodRouterTarget(serviceMethod, handlerExpr, handlerName)
                }

                base
            }
            else -> emptyList()
        }
    }

    /**
     * Extracts method-router targets from a call expression.
     *
     * Supports: get(handler), any(handler), on(MethodFilter::XXX, handler),
     *           get_service(svc), any_service(svc), on_service(MethodFilter::XXX, svc)
     */
    private fun extractMethodRouterFromCallExpr(call: RsCallExpr): List<MethodRouterTarget> {
        val callee = call.expr
        if (callee !is RsPathExpr) return emptyList()

        val name = callee.path.referenceName ?: return emptyList()
        val args = call.valueArgumentList

        // on(MethodFilter::XXX, handler) / on_service(MethodFilter::XXX, svc)
        if (name == "on" || name == "on_service") {
            val argList = args?.exprList ?: return emptyList()
            if (argList.size >= 2) {
                val method = extractMethodFilterFromExpr(argList[0])
                val handlerExpr = argList[1]
                val handlerName = extractHandlerName(handlerExpr)
                if (method != null) {
                    return listOf(MethodRouterTarget(method, handlerExpr, handlerName))
                }
            }
            return emptyList()
        }

        // any(handler): handles all HTTP methods.
        if (name == "any") {
            val handlerExpr = extractFirstArg(args)
            val handlerName = extractHandlerName(handlerExpr)
            return listOf(MethodRouterTarget("ANY", handlerExpr, handlerName))
        }

        // Standard methods: get/post/put/delete/...
        val method = METHOD_ATTRS[name]
        if (method != null) {
            val handlerExpr = extractFirstArg(args)
            val handlerName = extractHandlerName(handlerExpr)
            return listOf(MethodRouterTarget(method, handlerExpr, handlerName))
        }

        // *_service methods: get_service/post_service/any_service/...
        val serviceMethod = SERVICE_METHOD_ATTRS[name]
        if (serviceMethod != null) {
            val handlerExpr = extractFirstArg(args)
            val handlerName = extractHandlerName(handlerExpr)
            return listOf(MethodRouterTarget(serviceMethod, handlerExpr, handlerName))
        }

        return emptyList()
    }

    /**
     * Extracts MethodFilter value from an expression.
     *
     * Supports: MethodFilter::GET, MethodFilter::POST, etc.
     */
    private fun extractMethodFilterFromExpr(expr: RsExpr): String? {
        if (expr !is RsPathExpr) return null

        val pathText = expr.path.text
        // Match MethodFilter::GET, MethodFilter::POST, etc.
        if (pathText.startsWith("MethodFilter::")) {
            val filterName = pathText.removePrefix("MethodFilter::")
            return METHOD_FILTER_MAP[filterName]
        }

        return null
    }

    private fun extractRouterMethodName(call: RsCallExpr): String? {
        val name = when (val callee = call.expr) {
            is RsPathExpr -> callee.path.referenceName
            else -> null
        } ?: return null

        // axum routing funcs are lowercase: get/post/put/...
        // Also supports any() and *_service functions.
        return METHOD_ATTRS[name] ?: SERVICE_METHOD_ATTRS[name] ?: if (name == "any") "ANY" else null
    }

    private fun extractFirstArg(args: RsValueArgumentList?): RsExpr? {
        return args?.exprList?.firstOrNull()
    }

    private fun extractHandlerName(expr: RsExpr?): String? {
        val e = expr ?: return null
        return when (e) {
            is RsPathExpr -> e.path.referenceName
            else -> e.text
        }
    }

    private fun extractNestPath(mod: RsModItem): String? {
        val meta = mod.outerAttrList
            .map { it.metaItem }
            .firstOrNull { it.name == "nest" } ?: return null
        return extractFirstStringArg(meta)
    }

    // ==================== axum Router Call Chain Parsing ====================

    /**
     * Extracts all routes from a Router expression (supports route/nest/merge chaining).
     *
     * Example:
     * ```rust
     * Router::new()
     *     .route("/", get(handler))
     *     .nest("/api", Router::new()
     *         .route("/users", get(users_handler))
     *         .route("/posts", post(posts_handler)))
     *     .merge(other_router)
     * ```
     */
    fun extractAllRoutesFromRouterExpr(expr: RsExpr): List<RouteTarget> {
        return extractRoutesFromRouterExprWithPrefix(expr, "")
    }

    /**
     * Recursively parses Router expressions, collecting routes and applying prefixes.
     */
    private fun extractRoutesFromRouterExprWithPrefix(expr: RsExpr, prefix: String): List<RouteTarget> {
        return when {
            PsiCompat.isMethodCallExpr(expr) -> {
                val methodName = PsiCompat.getMethodCallName(expr)
                val receiver = PsiCompat.getMethodCallReceiver(expr)
                val args = PsiCompat.getMethodCallArgs(expr)

                when (methodName) {
                    "route" -> {
                        // .route("/path", get(handler))
                        val baseRoutes = if (receiver != null) extractRoutesFromRouterExprWithPrefix(receiver, prefix) else emptyList()
                        if (args.size >= 2) {
                            val path = (args[0] as? RsLitExpr)?.stringValue
                            if (path != null) {
                                val fullPath = joinPaths(prefix, path)
                                val methodRouters = extractMethodRouterTargets(args[1])
                                val newRoutes = methodRouters.map { r ->
                                    RouteTarget(r.method, fullPath, r.handlerExpr, r.handlerName)
                                }
                                baseRoutes + newRoutes
                            } else {
                                baseRoutes
                            }
                        } else {
                            baseRoutes
                        }
                    }
                    "nest" -> {
                        // .nest("/prefix", inner_router)
                        val baseRoutes = if (receiver != null) extractRoutesFromRouterExprWithPrefix(receiver, prefix) else emptyList()
                        if (args.size >= 2) {
                            val nestPath = (args[0] as? RsLitExpr)?.stringValue
                            if (nestPath != null) {
                                val newPrefix = joinPaths(prefix, nestPath)
                                val nestedRoutes = extractRoutesFromRouterExprWithPrefix(args[1], newPrefix)
                                baseRoutes + nestedRoutes
                            } else {
                                baseRoutes
                            }
                        } else {
                            baseRoutes
                        }
                    }
                    "merge" -> {
                        // .merge(other_router)
                        val baseRoutes = if (receiver != null) extractRoutesFromRouterExprWithPrefix(receiver, prefix) else emptyList()
                        if (args.isNotEmpty()) {
                            val mergedRoutes = extractRoutesFromRouterExprWithPrefix(args[0], prefix)
                            baseRoutes + mergedRoutes
                        } else {
                            baseRoutes
                        }
                    }
                    // Ignore methods that don't affect routes: with_state/layer/fallback/...
                    "with_state", "layer", "fallback", "fallback_service", "route_layer" -> {
                        if (receiver != null) extractRoutesFromRouterExprWithPrefix(receiver, prefix) else emptyList()
                    }
                    else -> {
                        // Unknown method: keep walking the receiver.
                        if (receiver != null) extractRoutesFromRouterExprWithPrefix(receiver, prefix) else emptyList()
                    }
                }
            }
            expr is RsCallExpr -> {
                // Router::new() or other function calls.
                val callee = expr.expr
                if (callee is RsPathExpr) {
                    val pathText = callee.path.text
                    // Router::new() returns an empty router.
                    if (pathText == "Router::new" || pathText.endsWith("::new")) {
                        emptyList()
                    } else {
                        // Likely a function returning Router; can't resolve statically.
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
            expr is RsPathExpr -> {
                // Variable reference (e.g. `other_router`); can't resolve statically.
                emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Returns true if the method call is part of a Router call chain.
     */
    fun isRouterChainMethod(call: RsMethodCall): Boolean {
        val name = call.referenceName
        return name in setOf("route", "nest", "merge", "with_state", "layer", "fallback", "fallback_service", "route_layer")
    }

    /**
     * Returns true if the method call defines routes (route or nest).
     */
    fun isRouteDefiningMethod(call: RsMethodCall): Boolean {
        val name = call.referenceName
        return name == "route" || name == "nest"
    }

    /**
     * Returns the full Router expression for a method call (walks up to the chain root).
     */
    fun getRouterChainRoot(call: RsMethodCall): RsExpr? {
        var current: RsExpr = (PsiCompat.findParentMethodCallExpr(call) as? RsExpr) ?: return null

        // Walk upwards until the parent is no longer part of the Router chain.
        while (true) {
            val parent = current.parent
            if (PsiCompat.isMethodCallExpr(parent)) {
                val parentCall = PsiCompat.getMethodCall(parent)
                if (parentCall != null && isRouterChainMethod(parentCall)) {
                    current = parent as RsExpr
                    continue
                }
            }
            break
        }

        return current
    }

    /**
     * Computes the prefix path for a RsMethodCall in the Router call chain.
     *
     * Example:
     * ```rust
     * Router::new()
     *     .nest("/api", Router::new()
     *         .route("/users", get(handler)))  // prefix is "/api"
     * ```
     */
    fun computeRouterNestPrefix(call: RsMethodCall): String {
        val segments = mutableListOf<String>()

        // Walk upwards from the current call, collecting all nest prefixes.
        var current = PsiCompat.findParentMethodCallExpr(call)

        while (current != null) {
            if (PsiCompat.isMethodCallExpr(current)) {
                val methodName = PsiCompat.getMethodCallName(current)
                if (methodName == "nest") {
                    // Check whether `call` is inside the second argument of this `nest`.
                    val args = PsiCompat.getMethodCallArgs(current)
                    if (args.size >= 2) {
                        val nestPath = (args[0] as? RsLitExpr)?.stringValue
                        val innerRouter = args[1]
                        // Check whether call is inside innerRouter.
                        if (nestPath != null && PsiTreeUtil.isAncestor(innerRouter, call, false)) {
                            segments.add(0, nestPath)
                        }
                    }
                }
            }

            // Continue walking upwards.
            current = PsiCompat.findParentMethodCallExpr(current)
        }

        // Merge all prefixes.
        var prefix = ""
        for (segment in segments) {
            prefix = joinPaths(prefix, segment)
        }
        return prefix
    }
}
