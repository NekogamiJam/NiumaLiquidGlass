/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original modification: standalone Trime integration helper for vivo native glass.
 */
package com.osfans.trime.ime.glass

import android.graphics.Color
import android.os.Looper
import android.view.View
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Ordinary vendor View reflection. The three geometry literals mirror the observed vivo call.
 * Width is a vendor raw integer, NOT a documented dp/px value. No extra View/material/animation.
 */
data class InnerStrokeLight(
    val color: Int = 0x99ffffff.toInt(),
    val intensity: Float = 0.55f,
    val geometry0: Float = 0f,
    val geometry1: Float = 2f,
    val geometry2: Float = 0f,
    val widthRaw: Int = 4,
    val saturation: Float = 0.3f,
)

/** Parameters passed to the vivo View material APIs. Corner values are caller-supplied pixels. */
data class Params(
    val enabled: Boolean = true,
    val blur: Float = 0.008f,
    val saturation: Float = 20f,
    val color1: Int = 0xfff2f2f2.toInt(),
    val opacity1: Float = 0.10f,
    val fusion1: Int = 0,
    val color2: Int = 0xfff2f2f2.toInt(),
    val opacity2: Float = 0f,
    val fusion2: Int = 0,
    val finalOpacity: Float = 1f,
    val finalFusion: Int = 0,
    val intensity: Float = 120f,
    val range: Float = 32f,
    val cornerTopLeftPx: Float = 0f,
    val cornerTopRightPx: Float = 0f,
    val cornerBottomLeftPx: Float = 0f,
    val cornerBottomRightPx: Float = 0f,
    /** Readable fallback shown when native material is unavailable or fails. */
    val fallbackColor: Int = 0xfff2f2f2.toInt(),
    /** setMaterial style; validated recipe uses 0. */
    val style: Int = 0,
    /** setMaterial content; validated recipe uses 2. */
    val content: Int = 2,
    val innerStroke: InnerStrokeLight? = null,
)

/** available means the required native API is present; success means the requested operation succeeded. */
data class Result(
    val available: Boolean,
    val success: Boolean,
    val message: String,
    val optionalFailures: List<String> = emptyList(),
    val innerStrokeRequested: Boolean = false,
    val innerStrokeApplied: Boolean = false,
    val innerStrokeFailure: String? = null,
) {
    val failed: Boolean get() = !success
}

/** Applies vivo's reflective native glass effect to exactly the supplied visible panel View. */
object NativeGlassRenderer {
    private data class Signature(val name: String, val types: List<Class<*>>)
    private val methods = ConcurrentHashMap<Signature, Method>()
    private val intType = Int::class.javaPrimitiveType!!
    private val floatType = Float::class.javaPrimitiveType!!
    private val booleanType = Boolean::class.javaPrimitiveType!!

    /** Apply only on the main thread, to an attached, hardware-accelerated visible panel. */
    @JvmStatic
    fun apply(panel: View, params: Params = Params()): Result {
        if (Looper.myLooper() != Looper.getMainLooper())
            return Result(false, false, "Must be called on the main thread")
        if (!panel.isAttachedToWindow)
            return Result(false, false, "Panel is not attached")
        if (panel.width <= 0 || panel.height <= 0 || panel.visibility != View.VISIBLE)
            return Result(false, false, "Panel is not visibly laid out")
        if (!panel.isHardwareAccelerated) {
            panel.setBackgroundColor(params.fallbackColor)
            return Result(false, false, "Hardware acceleration unavailable; fallback applied")
        }
        if (!params.enabled) {
            val cleared = clear(panel, params.fallbackColor)
            return cleared.copy(message = "Native glass disabled; ${cleared.message}")
        }

        // Only the supplied panel is made transparent; host/window and child backgrounds are untouched.
        panel.setBackgroundColor(Color.TRANSPARENT)
        val required = try {
            // Reset old state, then use the known-working ordinary View reflection path.
            call(panel, "clearMaterial")
            val materialResult = call(panel, "setMaterial", intType to params.style, intType to params.content)
            if (materialResult == false) throw NativeCallFailure("setMaterial returned false")
            call(panel, "setMaterialCustomized",
                floatType to params.blur, floatType to params.saturation,
                intType to params.color1, floatType to params.opacity1, intType to params.fusion1,
                intType to params.color2, floatType to params.opacity2, intType to params.fusion2,
                floatType to params.finalOpacity, intType to params.finalFusion)
            call(panel, "setGlassEffectCustomized", floatType to params.intensity, floatType to params.range)
            call(panel, "setMaterialCornerRadius",
                floatType to params.cornerTopLeftPx, floatType to params.cornerTopRightPx,
                floatType to params.cornerBottomLeftPx, floatType to params.cornerBottomRightPx)
            true
        } catch (t: Throwable) {
            // A core call can fail after setMaterial succeeded; do not leave a partially enabled effect.
            runCatching { call(panel, "clearMaterial") }
            panel.setBackgroundColor(params.fallbackColor)
            return Result(isNativeApiPresent(panel), false, "Native glass failed: ${rootMessage(t)}; fallback applied")
        }

        val optionalFailures = mutableListOf<String>()
        listOf(
            Triple("setMaterialForceUpdateBg", arrayOf(booleanType to false), "setMaterialForceUpdateBg(false)"),
            Triple("chooseMaterialBlurIteration", arrayOf(intType to 4), "chooseMaterialBlurIteration(4)"),
            Triple("setImageAlphaInBlurAlgorithm", arrayOf(booleanType to false), "setImageAlphaInBlurAlgorithm(false)"),
        ).forEach { (name, args, label) ->
            try { call(panel, name, *args) } catch (_: Throwable) { optionalFailures += label }
        }
        var strokeApplied = false
        var strokeFailure: String? = null
        params.innerStroke?.let { stroke ->
            try {
                val value = call(panel, "setGlassEffectInnerStrokeLight",
                    intType to stroke.color, floatType to stroke.intensity,
                    floatType to stroke.geometry0, floatType to stroke.geometry1,
                    floatType to stroke.geometry2, intType to stroke.widthRaw,
                    floatType to stroke.saturation)
                if (value == false) throw NativeCallFailure("setGlassEffectInnerStrokeLight returned false")
                strokeApplied = true
            } catch (t: Throwable) {
                // Optional edge-light failure must not discard the already working glass panel.
                strokeFailure = rootMessage(t)
                optionalFailures += "setGlassEffectInnerStrokeLight"
            }
        }
        panel.invalidate()
        return Result(required, true, "Native glass applied", optionalFailures,
            params.innerStroke != null, strokeApplied, strokeFailure)
    }

    /** Clears native state and restores a readable solid background. Safe to call repeatedly. */
    @JvmStatic
    fun clear(panel: View, fallbackColor: Int = 0xfff2f2f2.toInt()): Result {
        if (Looper.myLooper() != Looper.getMainLooper())
            return Result(false, false, "Must be called on the main thread")
        panel.setBackgroundColor(fallbackColor)
        if (!panel.isAttachedToWindow) return Result(false, true, "Detached; fallback applied")
        return try {
            call(panel, "clearMaterial")
            panel.invalidate()
            Result(true, true, "Native material cleared; fallback applied")
        } catch (t: Throwable) {
            Result(false, false, "Native clear unavailable: ${rootMessage(t)}; fallback applied")
        }
    }

    private fun isNativeApiPresent(panel: View): Boolean = try {
        resolve(panel, "setMaterial", intType, intType)
        resolve(panel, "setMaterialCustomized", floatType, floatType, intType, floatType, intType,
            intType, floatType, intType, floatType, intType)
        resolve(panel, "setGlassEffectCustomized", floatType, floatType)
        resolve(panel, "setMaterialCornerRadius", floatType, floatType, floatType, floatType)
        resolve(panel, "clearMaterial")
        true
    } catch (_: Throwable) { false }

    private class NativeCallFailure(message: String) : RuntimeException(message)

    private fun call(view: View, name: String, vararg args: Pair<Class<*>, Any>): Any? {
        val types = args.map { it.first }
        val method = resolve(view, name, *types.toTypedArray())
        return try {
            // A null return is normal for void methods; only Boolean.FALSE is interpreted as failure by caller.
            method.invoke(view, *args.map { it.second }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun resolve(view: View, name: String, vararg types: Class<*>): Method {
        val key = Signature(name, types.toList())
        methods[key]?.let { return it }
        val found = View::class.java.getDeclaredMethod(name, *types)
        found.isAccessible = true
        methods.putIfAbsent(key, found)
        return methods[key] ?: found
    }

    private fun rootMessage(t: Throwable): String =
        (if (t is InvocationTargetException) t.targetException else t).let { it.message ?: it.javaClass.simpleName }
}
