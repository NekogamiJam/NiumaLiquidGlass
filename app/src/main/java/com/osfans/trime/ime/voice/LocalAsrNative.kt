/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

/** Private CPU-only bridge. Every handle operation is serialized by OfflineVoiceInput. */
internal object LocalAsrNative {
    init { System.loadLibrary("niuma_offline_asr") }
    external fun create(model: String, tokens: String, vad: String): Long
    external fun reset(handle: Long)
    external fun accept(handle: Long, samples: FloatArray): Array<String>
    external fun finish(handle: Long): Array<String>
    external fun destroy(handle: Long)
}
