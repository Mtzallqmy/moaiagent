package com.agentdroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentdroid.core.localai.LlamaCppEngine
import com.agentdroid.core.localai.LocalGenerationConfig
import com.agentdroid.core.localai.LocalGenerationEvent
import com.agentdroid.core.localai.LocalModelDescriptor
import com.agentdroid.core.localai.LocalModelLoadConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LocalModelInferenceEvidenceTest {
    @Test fun realGgufLoadsAndStreamsTokens(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = File(context.getExternalFilesDir(null), "ci-models/SmolLM2-135M.Q4_K_M.gguf")
        assertTrue("CI GGUF smoke model missing at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals(EXPECTED_SHA256, sha256(modelFile))

        val engine = LlamaCppEngine()
        val (compatibility, metadata) = withTimeout(60_000) { engine.inspect(modelFile).getOrThrow() }
        assertTrue(compatibility.reason.orEmpty(), compatibility.supported)
        assertFalse("GGUF architecture metadata should be present", metadata.architecture.isNullOrBlank())

        val descriptor = LocalModelDescriptor(
            id = "ci-smollm2-135m",
            displayName = "SmolLM2 135M CI",
            fileName = modelFile.name,
            backend = engine.backend,
            sizeBytes = modelFile.length(),
            sha256 = EXPECTED_SHA256,
            importedAt = System.currentTimeMillis(),
            compatibility = compatibility,
            metadata = metadata
        )
        val session = withTimeout(90_000) {
            engine.load(descriptor, modelFile, LocalModelLoadConfig(contextSize = 512, threads = 2)).getOrThrow()
        }
        try {
            val startedAt = System.nanoTime()
            val events = withTimeout(120_000) {
                session.generate(
                    "The capital of France is",
                    LocalGenerationConfig(temperature = 0f, maxTokens = 16)
                ).toList()
            }
            val tokens = events.filterIsInstance<LocalGenerationEvent.Token>().map { it.text }
            assertTrue(events.firstOrNull() is LocalGenerationEvent.Started)
            assertTrue("Real llama.cpp inference produced no tokens", tokens.any { it.isNotBlank() })
            assertTrue("Real llama.cpp inference returned an error: $events", events.none { it is LocalGenerationEvent.Error })
            assertTrue(events.lastOrNull() is LocalGenerationEvent.Completed)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("Inference exceeded smoke-test bound: ${elapsedMs}ms", elapsedMs < 120_000)
            println("AGENTDROID_GGUF_OK architecture=${metadata.architecture} tokens=${tokens.size} elapsedMs=$elapsedMs text=${tokens.joinToString("").take(160)}")
        } finally {
            session.close()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val EXPECTED_SHA256 = "6ba74acd6239f2ae38abd1c941613b17f18f2687a79f40dc4b5fbe38ea61605c"
    }
}
