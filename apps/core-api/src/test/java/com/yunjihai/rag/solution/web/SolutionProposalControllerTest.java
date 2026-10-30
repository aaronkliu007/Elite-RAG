package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.proposal.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SolutionProposalControllerTest {
    @Test void usesAuthenticatedActorAndReturnsStructuredCandidateWithoutInternalKey() throws Exception {
        UUID actor = UUID.randomUUID(), solution = UUID.randomUUID(), version = UUID.randomUUID();
        var now = Instant.now();
        SolutionProposalPort proposals = new SolutionProposalPort() {
            public ProposalAttempt generateRequirements(SolutionProposalService.Command command) {
                org.assertj.core.api.Assertions.assertThat(command.actorId()).isEqualTo(actor);
                return attempt(command);
            }
            public ProposalAttempt generateOutline(SolutionProposalService.Command command) { return attempt(command); }
            public ProposalAttempt find(SolutionProposalService.Command command, ProposalKind kind) { return attempt(command); }
            private ProposalAttempt attempt(SolutionProposalService.Command command) {
                var request = new ProposalRegistrationRequest(new ProposalKey(command.actorId(), solution,
                        ProposalKind.REQUIREMENTS, command.clientKey()), version, 0, "a".repeat(64));
                return new ProposalAttempt(UUID.randomUUID(), request, ProposalStatus.SUCCEEDED,
                        Optional.of("{\"kind\":\"REQUIREMENTS\",\"items\":[{\"text\":\"需求候选\"}]}"),
                        Optional.empty(), now, now, now.plusSeconds(90));
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new SolutionProposalController(proposals))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var principal = new UsernamePasswordAuthenticationToken(actor.toString(), "ignored");
        mvc.perform(post("/api/v1/solutions/{id}/proposals/requirements", solution).principal(principal)
                        .header("Idempotency-Key", "private-key").contentType("application/json")
                        .content("{\"expectedRevision\":0,\"actorId\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].text").value("需求候选"))
                .andExpect(jsonPath("$.clientKey").doesNotExist()).andExpect(jsonPath("$.inputFingerprint").doesNotExist());
        mvc.perform(get("/api/v1/solutions/{id}/proposals/requirements", solution).principal(principal)
                        .header("Idempotency-Key", "private-key").param("expectedRevision", "0"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/solutions/{id}/proposals/requirements", solution).principal(principal)
                        .header("Idempotency-Key", "private-key").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
