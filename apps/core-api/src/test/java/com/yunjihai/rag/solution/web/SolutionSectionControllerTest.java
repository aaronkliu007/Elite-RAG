package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.solution.application.SolutionSectionAdoptionPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.port.SolutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SolutionSectionControllerTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Test void deniesGenerationBeforeCallingModelAndHidesSavedAttemptAfterRevocation() throws Exception {
        var fixture = new Fixture();
        fixture.granted.set(false);
        fixture.mvc.perform(post("/api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts",
                        SOLUTION, SECTION)
                        .principal(fixture.actor()).header("Idempotency-Key", "client-key")
                        .contentType("application/json")
                        .content("""
                                {"expectedRevision":3,"baselineSectionVersionId":null}
                                """))
                .andExpect(status().isNotFound());
        fixture.mvc.perform(get("/api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts",
                        SOLUTION, SECTION)
                        .principal(fixture.actor()).param("clientKey", "client-key"))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(fixture.generated).hasValue(0);
    }

    @Test void returnsCurrentAttemptStatusButRejectsStaleInput() throws Exception {
        var fixture = new Fixture();
        fixture.mvc.perform(get("/api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts",
                        SOLUTION, SECTION)
                        .principal(fixture.actor()).param("clientKey", "client-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sectionVersion").doesNotExist());
        fixture.mvc.perform(get("/api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts",
                        SOLUTION, UUID.randomUUID())
                        .principal(fixture.actor()).param("clientKey", "client-key"))
                .andExpect(status().isNotFound());
    }

    private static final class Fixture {
        final AtomicBoolean granted = new AtomicBoolean(true);
        final AtomicInteger generated = new AtomicInteger();
        final org.springframework.test.web.servlet.MockMvc mvc;

        Fixture() {
            var requirement = new RequirementVersion(UUID.randomUUID(), 1,
                    List.of(new RequirementItem(UUID.randomUUID(), RequirementCategory.UNKNOWN,
                            "待核实目标", List.of())), ACTOR, NOW, "初始录入");
            var outline = new OutlineVersion(UUID.randomUUID(), 1,
                    List.of(new OutlineNode(SECTION, "总体方案", 1, 1, true,
                            "描述方案", "方案检索", Set.of())), ACTOR, NOW);
            var solution = Solution.create(SOLUTION, SPACE, ACTOR, requirement, NOW)
                    .confirmRequirements(ACTOR, NOW, 0)
                    .reviseOutline(outline, 1).confirmOutline(ACTOR, NOW, 2);
            var attempt = GenerationAttempt.start(UUID.randomUUID(),
                    new GenerationKey("client-key", SOLUTION, requirement.id(),
                            outline.id(), SECTION, Optional.empty()), NOW);
            SolutionRepository solutions = new SolutionRepository() {
                public Optional<Solution> findById(UUID id) { return id.equals(SOLUTION)
                        ? Optional.of(solution) : Optional.empty(); }
                public void insert(Solution ignored) { throw new UnsupportedOperationException(); }
                public void replace(Solution ignored, long revision) { throw new UnsupportedOperationException(); }
            };
            GenerationAttemptStore attempts = new GenerationAttemptStore() {
                public Optional<GenerationAttempt> findByClientKey(String key) { return key.equals("client-key")
                        ? Optional.of(attempt) : Optional.empty(); }
                public GenerationAttemptRegistration register(GenerationAttempt ignored) {
                    throw new UnsupportedOperationException();
                }
                public void replace(GenerationAttempt ignored, long revision) {
                    throw new UnsupportedOperationException();
                }
            };
            SectionVersionRepository sections = new SectionVersionRepository() {
                public Optional<SectionVersion> findById(UUID id) { return Optional.empty(); }
                public SectionVersion insertIfAbsent(SectionVersion version) { throw new UnsupportedOperationException(); }
                public Optional<SectionVersion> findByAttemptId(UUID id) { return Optional.empty(); }
            };
            SectionGenerationPort generation = new SectionGenerationPort() {
                public SectionGenerationResult generate(GenerateSectionCommand command) {
                    generated.incrementAndGet();
                    return new SectionGenerationResult(attempt, Optional.empty());
                }
                public SectionGenerationResult readAttempt(UUID actor, UUID space, UUID id,
                        UUID section, String clientKey, Instant at) {
                    if (!id.equals(SOLUTION) || !section.equals(SECTION)
                            || !clientKey.equals("client-key")) throw new AccessDeniedAsNotFoundException();
                    return new SectionGenerationResult(attempt, Optional.empty());
                }
                public Solution adoptSection(UUID actor, UUID id, UUID version, long revision, Instant at) {
                    throw new UnsupportedOperationException();
                }
            };
            SolutionSectionAdoptionPort adoption = (actor, id, section, version, revision, key) -> {
                throw new UnsupportedOperationException();
            };
            var controller = new SolutionSectionController(generation, adoption, solutions,
                    sections, (actor, space, id, action, at) -> {
                        if (!granted.get()) throw new AccessDeniedAsNotFoundException();
                    }, Clock.fixed(NOW, ZoneOffset.UTC));
            mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new ApiExceptionHandler()).build();
        }

        UsernamePasswordAuthenticationToken actor() {
            return new UsernamePasswordAuthenticationToken(ACTOR.toString(), "", List.of());
        }
    }
}
