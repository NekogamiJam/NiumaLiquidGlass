// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

/** Pure cache: only a successful application may suppress a later equivalent request. */
class MaterialApplyGate<T : Any> {
    private var params: T? = null
    private var width = -1
    private var height = -1
    private var token: Any? = null
    private var valid = false

    fun needsApply(next: T, w: Int, h: Int, windowToken: Any?): Boolean =
        !valid || params != next || width != w || height != h || token !== windowToken

    fun applied(next: T, w: Int, h: Int, windowToken: Any?) {
        params = next; width = w; height = h; token = windowToken; valid = true
    }

    fun invalidate() { valid = false; params = null; token = null }
}
