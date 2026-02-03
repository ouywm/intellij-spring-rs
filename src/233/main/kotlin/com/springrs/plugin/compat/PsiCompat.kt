package com.springrs.plugin.compat

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.psi.RsDotExpr
import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsMethodCall
import org.rust.lang.core.psi.RsValueArgumentList

/**
 * PSI compatibility layer for platform version 233.
 *
 * In 233, [RsMethodCallExpr] does not exist. Instead, method calls are represented as:
 * [RsDotExpr] with a non-null [RsMethodCall] child.
 *
 * Structure in 233:
 * - RsDotExpr represents `receiver.something`
 *   - getExpr() → receiver expression
 *   - getMethodCall() → RsMethodCall if it's a method call (null for field access)
 *   - getFieldLookup() → RsFieldLookup if it's field access
 *
 * Structure in 251+:
 * - RsMethodCallExpr represents `receiver.method(args)` directly
 *   - getReceiver() → receiver expression
 *   - getMethodCall() → RsMethodCall
 */
object PsiCompat {

    /** Check if a PSI element is a method-call expression (RsDotExpr with methodCall). */
    fun isMethodCallExpr(element: PsiElement): Boolean {
        return element is RsDotExpr && element.methodCall != null
    }

    /** Get the method (reference) name from a method-call expression. */
    fun getMethodCallName(element: PsiElement): String? {
        val dotExpr = element as? RsDotExpr ?: return null
        val methodCall = dotExpr.methodCall ?: return null
        // RsMethodCall extends RsMethodOrField which has referenceName
        return (methodCall as? org.rust.lang.core.psi.ext.RsMethodOrField)?.referenceName
    }

    /** Get the receiver expression (e.g. `Router::new()` in `Router::new().route(...)`). */
    fun getMethodCallReceiver(element: PsiElement): RsExpr? {
        val dotExpr = element as? RsDotExpr ?: return null
        if (dotExpr.methodCall == null) return null
        return dotExpr.expr
    }

    /** Get the argument list of a method call expression. */
    fun getMethodCallArgs(element: PsiElement): List<RsExpr> {
        val dotExpr = element as? RsDotExpr ?: return emptyList()
        val methodCall = dotExpr.methodCall ?: return emptyList()
        return methodCall.valueArgumentList.exprList
    }

    /** Get the underlying RsMethodCall from a method-call expression. */
    fun getMethodCall(element: PsiElement): RsMethodCall? {
        val dotExpr = element as? RsDotExpr ?: return null
        return dotExpr.methodCall
    }

    /** Get the value argument list for chaining method calls. */
    fun getMethodCallValueArgumentList(element: PsiElement): RsValueArgumentList? {
        val dotExpr = element as? RsDotExpr ?: return null
        val methodCall = dotExpr.methodCall ?: return null
        return methodCall.valueArgumentList
    }

    /** Find the closest enclosing method-call expression (RsDotExpr with methodCall). */
    fun findParentMethodCallExpr(element: PsiElement): PsiElement? {
        var current = PsiTreeUtil.getParentOfType(element, RsDotExpr::class.java)
        while (current != null) {
            if (current.methodCall != null) {
                return current
            }
            current = PsiTreeUtil.getParentOfType(current, RsDotExpr::class.java)
        }
        return null
    }

    /** If the parent is a method-call expression return it, otherwise null. */
    fun getParentIfMethodCallExpr(element: PsiElement): PsiElement? {
        val parent = element.parent
        return if (parent is RsDotExpr && parent.methodCall != null) parent else null
    }
}