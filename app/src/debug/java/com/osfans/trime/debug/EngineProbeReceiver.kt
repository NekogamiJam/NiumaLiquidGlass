package com.osfans.trime.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.system.Os
import android.util.JsonReader
import android.util.Log
import com.osfans.trime.BuildConfig
import com.osfans.trime.core.Rime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only, isolated-process Rime JNI regression probe.
 * It never talks to an editor or the IME service and only records static table data.
 */
class EngineProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ENGINE_CHECK) return
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "engine check already running; ignored duplicate broadcast")
            return
        }
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread(
            {
                try {
                    EngineProbe(appContext).run()
                } catch (failure: Throwable) {
                    Log.e(TAG, "unhandled engine probe failure", failure)
                } finally {
                    pending.finish()
                    isRunning.set(false)
                }
            },
            "niuma-engine-check",
        ).start()
    }

    private class EngineProbe(private val context: Context) {
        private val runId = UUID.randomUUID().toString()
        private val startedAtElapsed = SystemClock.elapsedRealtime()
        private val startedAtEpoch = System.currentTimeMillis()
        private val outputFile = File(context.filesDir, OUTPUT_FILE_NAME)
        private val errors = JSONArray()
        private val inputHashes = JSONObject()
        private val report = JSONObject()
        private val mismatchExamples = JSONArray()
        private val selectionExamples = JSONArray()
        private val sampleSelections = mutableListOf<SelectionSample>()
        private var startupAttempted = false
        private var initialized = false
        private var testedCodes = 0
        private var totalExpectedCandidates = 0L
        private var candidateMismatchCount = 0
        private var keyInputFailureCount = 0
        private var selectionAttempts = 0
        private var selectionMismatchCount = 0
        private var lastInput = ""

        fun run() {
            report.put("format", RESULT_FORMAT)
            report.put("runId", runId)
            report.put("startedAtEpochMs", startedAtEpoch)
            report.put("status", "running")
            report.put("expectedFormat", EXPECTED_FORMAT)
            report.put("expectedJsonAsset", EXPECTED_ASSET)
            report.put("targetSchema", SCHEMA_ID)
            report.put("inputSha256", inputHashes)
            report.put("expectedCodeCount", JSONObject.NULL)
            report.put("testedCodeCount", 0)
            report.put("candidateMismatchCount", 0)
            report.put("init", JSONObject().put("attempted", false).put("ok", false))
            report.put("postselection", JSONObject().put("attempts", 0).put("mismatchCount", 0))
            report.put("errors", errors)
            writeReportAtomically()

            var runDirectory: File? = null
            try {
                val expectedInfo = loadAndCheckExpectedMetadata()
                report.put("inputSha256", inputHashes)
                report.put("expectedCodeCount", expectedInfo.codeCount)
                report.put("expectedCandidateCount", expectedInfo.candidateCount)
                report.put("expectedJsonSha256", expectedInfo.expectedJsonSha256)
                report.put("sourceOrder", JSONArray(expectedInfo.sourceOrder))
                updateProgress("copying_assets")

                val base = File(context.filesDir, "engine-probe")
                runDirectory = File(base, runId)
                val sharedDir = File(runDirectory, "shared")
                val userDir = File(runDirectory, "user")
                val logDir = File(runDirectory, "log")
                check(sharedDir.mkdirs() || sharedDir.isDirectory) { "cannot create isolated shared dir" }
                check(userDir.mkdirs() || userDir.isDirectory) { "cannot create isolated user dir" }
                check(logDir.mkdirs() || logDir.isDirectory) { "cannot create isolated log dir" }

                copySharedAssetTree("shared", sharedDir)
                verifyCopiedDictionaryHashes(expectedInfo.sourceHashes)
                File(userDir, "default.custom.yaml").writeText(
                    """patch:
  schema_list:
    - schema: niuma_glass
  ascii_mode: false
""".trimIndent() + "\n",
                    StandardCharsets.UTF_8,
                )
                report.put("runId", runId)
                report.put("isolatedDirs", JSONObject()
                    .put("shared", sharedDir.absolutePath)
                    .put("user", userDir.absolutePath)
                    .put("log", logDir.absolutePath))
                startupAttempted = true
                report.put("init", JSONObject().put("attempted", true).put("ok", false).put("fullCheck", false))
                updateProgress("initializing_rime")
                Rime.startupRime(
                    sharedDir.absolutePath,
                    userDir.absolutePath,
                    BuildConfig.BUILD_VERSION_NAME,
                    false,
                )
                val initWaitMs = waitForSchema()
                check(Rime.getCurrentRimeSchema() == SCHEMA_ID) {
                    "Rime did not select schema $SCHEMA_ID (current=${Rime.getCurrentRimeSchema()})"
                }
                Rime.setRimeOption("ascii_mode", false)
                check(!Rime.getRimeOption("ascii_mode") && !Rime.getRimeStatus().isAsciiMode) {
                    "ascii_mode remained on after explicit native reset"
                }
                initialized = true
                report.put("init", JSONObject()
                    .put("ok", true)
                    .put("schemaId", Rime.getCurrentRimeSchema())
                    .put("asciiMode", Rime.getRimeStatus().isAsciiMode)
                    .put("fullCheck", false)
                    .put("waitMs", initWaitMs)
                    .put("rimeUserDir", userDir.absolutePath)
                    .put("rimeLogDir", logDir.absolutePath)
                    .put("rimeNativeLogDestination", "logcat (RimeTraits.log_dir is empty in this build)"))

                updateProgress("checking_candidates")
                readExpectedAndRunCandidateSweep(expectedInfo)
                runPostselectionChecks()
                report.put("candidateMismatchCount", candidateMismatchCount)
                report.put("candidateMismatchExamples", mismatchExamples)
                report.put("keyInputFailureCount", keyInputFailureCount)
                report.put("postselection", JSONObject()
                    .put("sampleCodeCount", sampleSelections.size)
                    .put("attempts", selectionAttempts)
                    .put("mismatchCount", selectionMismatchCount)
                    .put("examples", selectionExamples))
                val passed = errors.length() == 0 && candidateMismatchCount == 0 &&
                    keyInputFailureCount == 0 && selectionMismatchCount == 0 &&
                    testedCodes == expectedInfo.codeCount
                report.put("status", if (passed) "passed" else "failed")
            } catch (failure: Throwable) {
                appendError("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
                report.put("status", "failed")
                report.put("failure", failure.javaClass.simpleName)
            } finally {
                if (startupAttempted) {
                    runCatching { Rime.clearRimeComposition() }
                        .onFailure { appendError("clearRimeComposition: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
                    runCatching { Rime.exitRime() }
                        .onFailure { appendError("exitRime: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
                }
                report.put("testedCodeCount", testedCodes)
                report.put("candidateMismatchCount", candidateMismatchCount)
                report.put("candidateMismatchExamples", mismatchExamples)
                report.put("keyInputFailureCount", keyInputFailureCount)
                report.put("postselection", JSONObject()
                    .put("sampleCodeCount", sampleSelections.size)
                    .put("attempts", selectionAttempts)
                    .put("mismatchCount", selectionMismatchCount)
                    .put("examples", selectionExamples))
                report.put("errors", errors)
                report.put("elapsedMs", SystemClock.elapsedRealtime() - startedAtElapsed)
                if (report.optString("status") == "running" || errors.length() != 0) report.put("status", "failed")
                runCatching { writeReportAtomically() }
                    .onFailure { Log.e(TAG, "could not write final report", it) }
                Log.i(TAG, "engine probe ${report.optString("status")}; report=${outputFile.absolutePath}")
            }
        }

        private fun readExpectedAndRunCandidateSweep(info: ExpectedInfo) {
            val actualHashes = readActualAssetHashes()
            for ((name, pinned) in PINNED_ASSET_SHA256) {
                check(actualHashes[name] == pinned) {
                    "APK shared asset SHA-256 mismatch for $name: expected $pinned, got ${actualHashes[name]}"
                }
            }
            inputHashes.put("niumawb.dict.yaml", actualHashes.getValue("niumawb.dict.yaml"))
            inputHashes.put("niumawb_zc.dict.yaml", actualHashes.getValue("niumawb_zc.dict.yaml"))
            inputHashes.put("niumawb_fh.dict.yaml", actualHashes.getValue("niumawb_fh.dict.yaml"))
            report.put("inputSha256", inputHashes)
            check(info.sourceHashes == actualHashes) { "expected map and APK dictionary hashes differ" }
            writeReportAtomically()

            val expectedSha = MessageDigest.getInstance("SHA-256")
            context.assets.open(EXPECTED_ASSET).use { input ->
                val digestInput = java.security.DigestInputStream(input, expectedSha)
                JsonReader(InputStreamReader(digestInput, StandardCharsets.UTF_8)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    var sawCandidates = false
                    var previousCode = ""
                    reader.skipValueFieldsUntilCandidates { code, expected ->
                        sawCandidates = true
                        check(code.matches(Regex("[a-z]{1,5}"))) { "invalid expected code: $code" }
                        check(previousCode.isEmpty() || previousCode < code) { "expected codes are not strictly sorted" }
                        previousCode = code
                        totalExpectedCandidates += expected.size
                        testedCodes++
                        if (sampleSelections.size < MAX_SELECTION_SAMPLE_CODES && expected.size >= 3) {
                            sampleSelections += SelectionSample(code, expected.toList())
                        }
                        compareForCode(code, expected)
                        if (testedCodes % PROGRESS_EVERY_CODES == 0) updateProgress("checking_candidates")
                    }
                    reader.endObject()
                    check(sawCandidates) { "expected map lacks candidates object" }
                }
            }
            check(testedCodes == info.codeCount) {
                "expected map code_count ${info.codeCount} but read $testedCodes entries"
            }
            check(totalExpectedCandidates == info.candidateCount) {
                "expected candidate count ${info.candidateCount} but read $totalExpectedCandidates"
            }
            report.put("testedCodeCount", testedCodes)
            report.put("expectedCandidateCount", totalExpectedCandidates)
            report.put("candidateMismatchCount", candidateMismatchCount)
            report.put("candidateMismatchExamples", mismatchExamples)
            report.put("keyInputFailureCount", keyInputFailureCount)
            writeReportAtomically()
        }

        private fun JsonReader.skipValueFieldsUntilCandidates(onCandidate: (String, List<String>) -> Unit) {
            while (hasNext()) {
                when (nextName()) {
                    "candidates" -> {
                        beginObject()
                        while (hasNext()) {
                            val code = nextName()
                            onCandidate(code, readStringArray(this))
                        }
                        endObject()
                        return
                    }
                    else -> skipValue()
                }
            }
        }

        private fun compareForCode(code: String, expected: List<String>) {
            val common = commonPrefixLength(lastInput, code)
            for (index in lastInput.length downTo common + 1) {
                if (!Rime.processRimeKey(0xff08, 0)) {
                    keyInputFailureCount++
                    appendError("Rime rejected backspace while moving from $lastInput to $code")
                }
            }
            for (index in common until code.length) {
                val keyCode = code[index].code
                if (!Rime.processRimeKey(keyCode, 0)) {
                    keyInputFailureCount++
                    appendError("Rime rejected a code key while testing $code")
                }
            }
            lastInput = code
            val actual = Rime.getRimeCandidates(0, expected.size + 1).map { it.text }
            if (actual != expected) {
                candidateMismatchCount++
                if (mismatchExamples.length() < MAX_EXAMPLES) {
                    mismatchExamples.put(JSONObject()
                        .put("code", code)
                        .put("expected", JSONArray(expected))
                        .put("actual", JSONArray(actual)))
                }
            }
        }

        private fun runPostselectionChecks() {
            if (sampleSelections.isEmpty()) {
                appendError("no code with at least three expected candidates available for postselection checks")
                return
            }
            for (sample in sampleSelections) {
                val indexes = listOf(0, sample.expected.size / 2, sample.expected.lastIndex).distinct()
                for (index in indexes) {
                    Rime.clearRimeComposition()
                    lastInput = ""
                    enterCode(sample.code)
                    val candidates = Rime.getRimeCandidates(0, sample.expected.size + 1).map { it.text }
                    if (candidates != sample.expected) {
                        selectionMismatchCount++
                        addSelectionExample(sample.code, index, sample.expected, candidates, "preselect_order")
                    }
                    val selected = Rime.selectRimeCandidate(index, true)
                    val commitTextWasPresent = Rime.getRimeCommit().text != null // Deliberately discard the text.
                    selectionAttempts++
                    if (!selected) {
                        appendError("Rime rejected global candidate selection index $index for ${sample.code}")
                    }
                    Rime.clearRimeComposition()
                    lastInput = ""
                    enterCode(sample.code)
                    val after = Rime.getRimeCandidates(0, sample.expected.size + 1).map { it.text }
                    if (after != sample.expected) {
                        selectionMismatchCount++
                        addSelectionExample(sample.code, index, sample.expected, after, "postselect_order")
                    }
                    if (selectionExamples.length() < MAX_EXAMPLES && (!selected || !commitTextWasPresent)) {
                        selectionExamples.put(JSONObject()
                            .put("code", sample.code)
                            .put("candidateIndex", index)
                            .put("selected", selected)
                            .put("commitObserved", commitTextWasPresent))
                    }
                }
            }
            Rime.clearRimeComposition()
            lastInput = ""
        }

        private fun enterCode(code: String) {
            for (character in code) {
                val keyCode = character.code
                if (!Rime.processRimeKey(keyCode, 0)) {
                    keyInputFailureCount++
                    appendError("Rime rejected a code key during selection check for $code")
                }
            }
            lastInput = code
        }

        private fun addSelectionExample(
            code: String,
            index: Int,
            expected: List<String>,
            actual: List<String>,
            phase: String,
        ) {
            if (selectionExamples.length() >= MAX_EXAMPLES) return
            selectionExamples.put(JSONObject()
                .put("phase", phase)
                .put("code", code)
                .put("candidateIndex", index)
                .put("expected", JSONArray(expected))
                .put("actual", JSONArray(actual)))
        }

        private fun waitForSchema(): Long {
            val started = SystemClock.elapsedRealtime()
            val deadline = started + SCHEMA_WAIT_LIMIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val available = Rime.getRimeSchemaList().any { it.id == SCHEMA_ID }
                // Maintenance publishes the schema list before compilation/session creation is ready.
                // A failed selection during this interval is readiness, not a table-order failure.
                if (available && Rime.selectRimeSchema(SCHEMA_ID) && Rime.getCurrentRimeSchema() == SCHEMA_ID) {
                    return SystemClock.elapsedRealtime() - started
                }
                Thread.sleep(SCHEMA_POLL_MS)
            }
            error("schema $SCHEMA_ID not available after ${SCHEMA_WAIT_LIMIT_MS}ms")
        }

        private fun loadAndCheckExpectedMetadata(): ExpectedInfo {
            val expectedSha = MessageDigest.getInstance("SHA-256")
            var format: String? = null
            var schemaId: String? = null
            var codeCount: Int? = null
            var candidateCount: Long? = null
            var sourceHashes: Map<String, String>? = null
            var sourceOrder: List<String>? = null
            context.assets.open(EXPECTED_ASSET).use { input ->
                val digestInput = java.security.DigestInputStream(input, expectedSha)
                JsonReader(InputStreamReader(digestInput, StandardCharsets.UTF_8)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "format" -> format = reader.nextString()
                            "schema_id" -> schemaId = reader.nextString()
                            "code_count" -> codeCount = reader.nextInt()
                            "unique_candidate_count" -> candidateCount = reader.nextLong()
                            "source_sha256" -> sourceHashes = readStringMap(reader)
                            "source_order" -> sourceOrder = readStringArray(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            val jsonHash = expectedSha.digest().toHex()
            check(format == EXPECTED_FORMAT) { "unexpected expected-map format: $format" }
            check(schemaId == SCHEMA_ID) { "expected map schema mismatch: $schemaId" }
            check(codeCount != null && codeCount!! > 0) { "expected map has no code_count" }
            check(candidateCount != null && candidateCount!! > 0) { "expected map has no candidate count" }
            check(sourceOrder == EXPECTED_SOURCE_ORDER) { "expected map source order mismatch: $sourceOrder" }
            val hashes = sourceHashes ?: error("expected map lacks source_sha256")
            for ((name, pinned) in PINNED_ASSET_SHA256) {
                check(hashes[name] == pinned) { "expected map pinned hash mismatch for $name" }
            }
            return ExpectedInfo(codeCount!!, candidateCount!!, hashes, sourceOrder!!, jsonHash)
        }

        private fun readActualAssetHashes(): Map<String, String> {
            val assetHashes = linkedMapOf<String, String>()
            val sharedRoot = File(context.filesDir, "engine-probe/$runId/shared")
            for ((name, _) in PINNED_ASSET_SHA256) {
                val copied = File(sharedRoot, name)
                check(copied.isFile) { "copied APK asset missing: ${copied.absolutePath}" }
                assetHashes[name] = sha256(copied.readBytes())
            }
            return assetHashes
        }

        private fun verifyCopiedDictionaryHashes(expectedHashes: Map<String, String>) {
            val actual = readActualAssetHashes()
            for ((name, pinned) in PINNED_ASSET_SHA256) {
                check(actual[name] == pinned) {
                    "APK shared asset SHA-256 mismatch for $name: expected $pinned, got ${actual[name]}"
                }
                check(expectedHashes[name] == actual[name]) {
                    "expected-map and copied APK asset SHA-256 differ for $name"
                }
            }
            inputHashes.put("niumawb.dict.yaml", actual.getValue("niumawb.dict.yaml"))
            inputHashes.put("niumawb_zc.dict.yaml", actual.getValue("niumawb_zc.dict.yaml"))
            inputHashes.put("niumawb_fh.dict.yaml", actual.getValue("niumawb_fh.dict.yaml"))
            report.put("inputSha256", inputHashes)
        }

        private fun copySharedAssetTree(assetPath: String, destination: File) {
            if (assetPath.split('/').any { it == "build" }) return
            val children = context.assets.list(assetPath).orEmpty()
            if (children.isNotEmpty()) {
                for (child in children) {
                    copySharedAssetTree("$assetPath/$child", destination)
                }
                return
            }
            if (!isAllowedSharedAsset(assetPath)) return
            val relative = assetPath.removePrefix("shared/")
            val target = File(destination, relative)
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }

        private fun isAllowedSharedAsset(path: String): Boolean {
            val lower = path.lowercase()
            if (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".txt")) return true
            return lower.startsWith("shared/opencc/") && lower.endsWith(".json")
        }

        private fun updateProgress(phase: String) {
            report.put("status", "running")
            report.put("phase", phase)
            report.put("testedCodeCount", testedCodes)
            report.put("candidateMismatchCount", candidateMismatchCount)
            report.put("candidateMismatchExamples", mismatchExamples)
            report.put("keyInputFailureCount", keyInputFailureCount)
            report.put("elapsedMs", SystemClock.elapsedRealtime() - startedAtElapsed)
            writeReportAtomically()
        }

        private fun writeReportAtomically() {
            outputFile.parentFile?.mkdirs()
            val temp = File(outputFile.parentFile, "${outputFile.name}.$runId.tmp")
            FileOutputStream(temp).use { stream ->
                stream.write((report.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Os.rename(temp.absolutePath, outputFile.absolutePath)
        }

        private fun appendError(message: String) {
            if (errors.length() < MAX_ERRORS) errors.put(message.take(MAX_ERROR_CHARS))
            report.put("errors", errors)
        }

        private fun commonPrefixLength(left: String, right: String): Int {
            var index = 0
            while (index < left.length && index < right.length && left[index] == right[index]) index++
            return index
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun readStringMap(reader: JsonReader): Map<String, String> {
            val values = linkedMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) values[reader.nextName()] = reader.nextString()
            reader.endObject()
            return values
        }

        private fun readStringArray(reader: JsonReader): List<String> {
            val values = mutableListOf<String>()
            reader.beginArray()
            while (reader.hasNext()) values += reader.nextString()
            reader.endArray()
            return values
        }

        private data class ExpectedInfo(
            val codeCount: Int,
            val candidateCount: Long,
            val sourceHashes: Map<String, String>,
            val sourceOrder: List<String>,
            val expectedJsonSha256: String,
        )

        private data class SelectionSample(val code: String, val expected: List<String>)
    }

    companion object {
        fun runIsolatedProbe(context: Context) {
            check(BuildConfig.DEBUG && android.app.Application.getProcessName().endsWith(":enginecheck"))
            check(isRunning.compareAndSet(false, true)) { "Engine check already running" }
            try { EngineProbe(context.applicationContext).run() } finally { isRunning.set(false) }
        }
        const val ACTION_ENGINE_CHECK = "local.niuma.glass.debug.ENGINE_CHECK"
        private const val TAG = "NiumaEngineProbe"
        private const val EXPECTED_ASSET = "niuma/debug_expected.json"
        private const val OUTPUT_FILE_NAME = "android-engine-check.json"
        private const val RESULT_FORMAT = "niuma-android-engine-check-v1"
        private const val EXPECTED_FORMAT = "niuma-debug-expected-v1"
        private const val SCHEMA_ID = "niuma_glass"
        private const val SCHEMA_WAIT_LIMIT_MS = 120_000L
        private const val SCHEMA_POLL_MS = 250L
        private const val PROGRESS_EVERY_CODES = 2_500
        private const val MAX_SELECTION_SAMPLE_CODES = 3
        private const val MAX_EXAMPLES = 20
        private const val MAX_ERRORS = 20
        private const val MAX_ERROR_CHARS = 300
        private val EXPECTED_SOURCE_ORDER = listOf("niumawb_zc.dict.yaml", "niumawb_fh.dict.yaml")
        private val PINNED_ASSET_SHA256 = linkedMapOf(
            "niumawb.dict.yaml" to "294e03d15a30f9a53ff78d7b9a5a0060f1dc71a2600ace0091d2765ed6ace92e",
            "niumawb_zc.dict.yaml" to "09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9",
            "niumawb_fh.dict.yaml" to "05b9981ba4aa20153012bc7762b137a0a29ed8bb8de7c17f2843a079a3466e2b",
        )
        private val isRunning = AtomicBoolean(false)
    }
}
