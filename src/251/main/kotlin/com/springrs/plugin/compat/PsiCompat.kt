package com.springrs.plugin.compat

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsMethodCall
import org.rust.lang.core.psi.RsMethodCallExpr
import org.rust.lang.core.psi.RsValueArgumentList

/**
 * PSI compatibility layer for platform version 252+.
 *
 * In 252+, [RsMethodCallExpr] is a first-class PSI element representing `receiver.method(args)`.
 */
object PsiCompat {

    /** Check if a PSI element is a method-call expression. */
    fun isMethodCallExpr(element: PsiElement): Boolean = element is RsMethodCallExpr

    /** Get the method (reference) name from a method-call expression, e.g. "route", "nest". */
    fun getMethodCallName(element: PsiElement): String? =
        (element as? RsMethodCallExpr)?.methodCall?.referenceName

    /** Get the receiver expression, e.g. `Router::new()` in `Router::new().route(...)`. */
    fun getMethodCallReceiver(element: PsiElement): RsExpr? =
        (element as? RsMethodCallExpr)?.receiver

    /** Get the argument list of a method call expression. */
    fun getMethodCallArgs(element: PsiElement): List<RsExpr> =
        (element as? RsMethodCallExpr)?.methodCall?.valueArgumentList?.exprList ?: emptyList()

    /** Get the underlying RsMethodCall from a method-call expression. */
    fun getMethodCall(element: PsiElement): RsMethodCall? =
        (element as? RsMethodCallExpr)?.methodCall

    /** Get the value argument list for chaining method calls. */
    fun getMethodCallValueArgumentList(element: PsiElement): RsValueArgumentList? =
        (element as? RsMethodCallExpr)?.methodCall?.valueArgumentList

    /** Find the closest enclosing method-call expression. */
    fun findParentMethodCallExpr(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, RsMethodCallExpr::class.java)

    /** If the parent is a method-call expression return it, otherwise null. */
    fun getParentIfMethodCallExpr(element: PsiElement): PsiElement? {
        val parent = element.parent
        return parent as? RsMethodCallExpr
    }
}
