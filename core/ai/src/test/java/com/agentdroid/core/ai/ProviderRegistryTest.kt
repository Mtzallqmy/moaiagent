package com.agentdroid.core.ai

import com.agentdroid.core.model.ProviderKind
import com.agentdroid.core.ai.providers.FakeAiProvider
import org.junit.Test
import org.junit.Assert.assertEquals

class ProviderRegistryTest {
    @Test fun registryResolvesProvidersPolymorphically() {
        val registry = ProviderRegistry(listOf(FakeAiProvider()))
        assertEquals("fake", registry.get(ProviderKind.FAKE)?.kind?.name?.lowercase() ?: "missing")
        assertEquals(1, registry.all().size)
    }
}
