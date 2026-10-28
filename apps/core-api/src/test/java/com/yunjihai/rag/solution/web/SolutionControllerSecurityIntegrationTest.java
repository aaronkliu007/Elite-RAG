package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.api.RequestTraceFilter;
import com.yunjihai.rag.identity.*;
import com.yunjihai.rag.identity.web.*;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SolutionControllerSecurityIntegrationTest.Config.class, properties = {
        "rag.session.enabled=true",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@AutoConfigureMockMvc
class SolutionControllerSecurityIntegrationTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID SPACE = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final UUID SOLUTION = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID REQUIREMENT = UUID.fromString("00000000-0000-7000-8000-000000000004");
    private static final AtomicBoolean SOURCE_GRANTED = new AtomicBoolean(true);
    @Autowired MockMvc mvc;

    @Test void requiresSessionAndHidesUnauthorizedSpace() throws Exception {
        mvc.perform(get("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/knowledge-spaces/{spaceId}/solutions", UUID.randomUUID())
                        .with(authentication(session())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void requiresCsrfAndIdempotencyKeyForCreate() throws Exception {
        String body = """
                {"name":"项目方案","expectedRevision":0,"items":[{"category":"UNKNOWN","text":"待确认","sources":[]}]}
                """;
        mvc.perform(post("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE)
                        .with(authentication(session())).contentType("application/json")
                        .header("Idempotency-Key", "key-1").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE)
                        .with(authentication(session())).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test void projectsConflictGateAndAuthorityErrorsWithoutBody() throws Exception {
        String body = """
                {"name":"项目方案","expectedRevision":0,"items":[{"category":"UNKNOWN","text":"待确认","sources":[]}]}
                """;
        mvc.perform(post("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE)
                        .with(authentication(session())).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "conflict").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.items").doesNotExist());
        mvc.perform(post("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE)
                        .with(authentication(session())).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "gate").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REQUIREMENTS_NOT_CONFIRMED"));
        mvc.perform(post("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE)
                        .with(authentication(session())).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "unavailable").content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOLUTION_AUTHORITY_UNAVAILABLE"));
    }

    @Test void listsOnlyAuthorizedSpaceAndRedactsRevokedSourceDetail() throws Exception {
        mvc.perform(get("/api/v1/knowledge-spaces/{spaceId}/solutions", SPACE)
                        .with(authentication(session())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("测试方案"));
        SOURCE_GRANTED.set(false);
        try {
            mvc.perform(get("/api/v1/solutions/{solutionId}", SOLUTION)
                            .with(authentication(session())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sourceAccessValid").value(false))
                    .andExpect(jsonPath("$.requirementVersion").isEmpty())
                    .andExpect(jsonPath("$.name").value("测试方案"));
        } finally {
            SOURCE_GRANTED.set(true);
        }
    }

    private static UsernamePasswordAuthenticationToken session() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(ACTOR, "alice", "Alice", 0, NOW), null, List.of());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class, RequestTraceFilter.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean InMemorySessionVersionStore versions() { return new InMemorySessionVersionStore(); }
        @Bean SessionRevocationService sessions(InMemorySessionVersionStore versions) {
            return new SessionRevocationService(versions);
        }
        @Bean SolutionController solutionController() {
            var initial = new RequirementVersion(REQUIREMENT, 1,
                    List.of(new RequirementItem(UUID.randomUUID(), RequirementCategory.GOAL,
                            "历史正文", List.of(new RequirementSource(new DocumentVersionRef(
                                    UUID.randomUUID(), UUID.randomUUID()), new PageLocator(1, null))))),
                    ACTOR, NOW, "初始录入");
            var sample = Solution.create(SOLUTION, SPACE, ACTOR, SolutionName.of("测试方案"), initial, NOW);
            SolutionCommandPort commands = new SolutionCommandPort() {
                @Override public SolutionCommandResult create(UUID actor, String key,
                        SolutionCommandService.CreateInput input) {
                    return switch (key) {
                        case "conflict" -> throw new SolutionCommandConflictException("IDEMPOTENCY_CONFLICT");
                        case "gate" -> throw new SolutionLifecycleException("REQUIREMENTS_NOT_CONFIRMED");
                        case "unavailable" -> throw new SolutionAuthorityUnavailableException();
                        default -> new SolutionCommandResult(UUID.randomUUID(), 0, UUID.randomUUID(),
                                Optional.empty(), SolutionPhase.REQUIREMENTS_DRAFT);
                    };
                }
                @Override public SolutionCommandResult reviseRequirements(UUID actor, String key,
                        SolutionCommandService.ReviseRequirementsInput input) { throw new UnsupportedOperationException(); }
                @Override public SolutionCommandResult confirmRequirements(UUID actor, String key,
                        SolutionCommandService.ConfirmInput input) { throw new UnsupportedOperationException(); }
                @Override public SolutionCommandResult reviseOutline(UUID actor, String key,
                        SolutionCommandService.ReviseOutlineInput input) { throw new UnsupportedOperationException(); }
                @Override public SolutionCommandResult confirmOutline(UUID actor, String key,
                        SolutionCommandService.ConfirmInput input) { throw new UnsupportedOperationException(); }
            };
            SolutionRepository solutions = new SolutionRepository() {
                @Override public Optional<Solution> findById(UUID id) {
                    return SOLUTION.equals(id) ? Optional.of(sample) : Optional.empty();
                }
                @Override public void insert(Solution solution) { throw new UnsupportedOperationException(); }
                @Override public void replace(Solution solution, long expected) { throw new UnsupportedOperationException(); }
            };
            RequirementVersionRepository requirements = new RequirementVersionRepository() {
                @Override public Optional<RequirementVersion> findById(UUID id) {
                    return REQUIREMENT.equals(id) ? Optional.of(initial) : Optional.empty();
                }
                @Override public void insert(RequirementVersion version) { throw new UnsupportedOperationException(); }
            };
            OutlineVersionRepository outlines = new OutlineVersionRepository() {
                @Override public Optional<OutlineVersion> findById(UUID id) { return Optional.empty(); }
                @Override public void insert(OutlineVersion version) { throw new UnsupportedOperationException(); }
            };
            RoleAssignmentStore roles = new RoleAssignmentStore() {
                @Override public boolean hasSystemRole(UUID user, SystemRole role) { return false; }
                @Override public boolean hasAnySystemRole(SystemRole role) { return false; }
                @Override public Set<SpaceRole> activeSpaceRoles(UUID user, UUID space) { return Set.of(SpaceRole.EDITOR); }
                @Override public void grantSystemRole(UUID user, SystemRole role, UUID actor, Instant at) { }
                @Override public void grantSpaceRole(UUID space, UUID user, SpaceRole role, UUID actor, Instant at) { }
                @Override public void revokeSpaceRole(UUID space, UUID user, SpaceRole role, UUID actor, Instant at) { }
            };
            var access = new SolutionAccessService((user, space, at) -> {
                if (!SPACE.equals(space)) throw new AccessDeniedAsNotFoundException();
            }, roles);
            var sources = new SolutionSourceAccess((user, space, at) -> {},
                    (user, space, document, version, at) -> SOURCE_GRANTED.get());
            SolutionQueryPort query = (space, page, pageSize) -> SPACE.equals(space)
                    ? List.of(new SolutionQueryPort.SolutionListItem(SOLUTION, SPACE, "测试方案",
                            sample.phase(), sample.revision(), NOW, NOW)) : List.of();
            return new SolutionController(commands, solutions, query, requirements, outlines,
                    access, sources, new SolutionReviewPolicy(false), Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}
