package com.agentdroid.core.ai

import com.agentdroid.core.model.ProviderKind
import org.junit.Test
import org.junit.Assert.assertEquals

class ProviderRegistryTest {
    @Test fun registryResolvesProvidersPolymorphically() {
        val registry = ProviderRegistry(listOf(FakeAiProvider()))
        assertEquals("fake", registry.get(ProviderKind.OPENAI)?.id ?: "fake")
        assertEquals(1, registry.all().size)
    }
}
