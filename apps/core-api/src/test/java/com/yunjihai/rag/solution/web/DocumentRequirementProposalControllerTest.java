package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.proposal.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentRequirementProposalControllerTest {
    @Test void usesAuthenticatedActorAndSameRequestBodyForGenerationAndLookup() throws Exception {
        var actor = UUID.randomUUID(); var solution = UUID.randomUUID(); var version = UUID.randomUUID();
        var doc = UUID.randomUUID(); var docVersion = UUID.randomUUID(); var now = Instant.now();
        var seen = new AtomicReference<DocumentRequirementProposalService.Command>();
        DocumentRequirementProposalPort port = new DocumentRequirementProposalPort() {
            public ProposalAttempt generate(DocumentRequirementProposalService.Command input) { seen.set(input); return result(input); }
            public ProposalAttempt find(DocumentRequirementProposalService.Command input) { seen.set(input); return result(input); }
            private ProposalAttempt result(DocumentRequirementProposalService.Command input) {
                var request = new ProposalRegistrationRequest(new ProposalKey(input.actorId(), solution,
                        ProposalKind.DOCUMENT_REQ, input.clientKey()), version, 2, "a".repeat(64));
                return new ProposalAttempt(UUID.randomUUID(), request, ProposalStatus.SUCCEEDED,
                        Optional.of("{\"kind\":\"REQUIREMENTS\",\"items\":[]}"), Optional.empty(), now, now, now.plusSeconds(90));
            }
        };
        DocumentRequirementProposalAdoptionPort adoption = (input, key) -> {
            seen.set(input);
            assertThat(key).isEqualTo("adoption-key");
            return new SolutionCommandResult(solution, 3, version, Optional.empty(), com.yunjihai.rag.solution.domain.SolutionPhase.REQUIREMENTS_DRAFT);
        };
        DocumentRequirementEvidencePreviewPort preview = (input, evidenceId) -> {
            seen.set(input); return new DocumentRequirementEvidencePreviewService.Preview("核对片段");
        };
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentRequirementProposalController(port, adoption, preview))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var principal = new UsernamePasswordAuthenticationToken(actor.toString(), "ignored");
        var body = "{\"expectedRevision\":2,\"query\":\"客户需求\",\"documents\":[{\"documentId\":\""
                + doc + "\",\"versionId\":\"" + docVersion + "\"}],\"actorId\":\"forged\"}";
        for (var suffix : List.of("", "/lookup")) {
            mvc.perform(post("/api/v1/solutions/{id}/document-requirement-proposals" + suffix, solution)
                            .principal(principal).header("Idempotency-Key", "key").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("DOCUMENT_REQ"))
                    .andExpect(jsonPath("$.clientKey").doesNotExist());
            assertThat(seen.get().actorId()).isEqualTo(actor);
            assertThat(seen.get().documents()).singleElement().satisfies(ref -> {
                assertThat(ref.documentId()).isEqualTo(doc);
                assertThat(ref.versionId()).isEqualTo(docVersion);
            });
        }
        mvc.perform(post("/api/v1/solutions/{id}/document-requirement-proposals/adopt", solution)
                        .principal(principal).header("Proposal-Key", "key").header("Idempotency-Key", "adoption-key")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        assertThat(seen.get().clientKey()).isEqualTo("key");
        mvc.perform(post("/api/v1/solutions/{id}/document-requirement-proposals/evidence/{evidence}", solution, UUID.randomUUID())
                        .principal(principal).header("Proposal-Key", "key").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("核对片段"));
    }
}
