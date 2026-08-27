package com.agentdroid.core.artifacts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class CitationValidatorTest {
    @Test fun onlyAcceptsSourcesPresentInResearchSession(): Unit = runBlocking {
        val validator = CitationValidator(CitationSourceCatalog { session, source, url ->
            session == "r1" && source == "s1" && url == "https://example.com/a"
        })
        validator.validate(listOf(SourceReference("r1", "s1", "https://example.com/a")))
        assertThrows(InvalidCitation::class.java) {
            runBlocking { validator.validate(listOf(SourceReference("r1", "invented", "https://example.com/a"))) }
        }
        assertThrows(InvalidCitation::class.java) {
            runBlocking { validator.validate(listOf(SourceReference("r1", "s1", "file:///secret"))) }
        }
    }

    @Test fun rejectsDuplicateReferences(): Unit = runBlocking {
        val validator = CitationValidator(CitationSourceCatalog { _, _, _ -> true })
        val reference = SourceReference("r1", "s1", "https://example.com/a")
        assertThrows(InvalidCitation::class.java) { runBlocking { validator.validate(listOf(reference, reference)) } }
    }

    @Test fun reportCannotCiteAnUnattachedSource() {
        assertThrows(IllegalArgumentException::class.java) {
            DefaultArtifactGenerator().generate(ArtifactGenerationRequest(
                type = ArtifactType.REPORT, title = "Research",
                findings = listOf(ReportFinding("Finding", "Details", listOf("invented")))
            ))
        }
    }
}
