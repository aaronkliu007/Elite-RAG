package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.RequestTraceFilter;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.knowledge.DocumentCreationAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class UploadAuthorizationFilterTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000901");
    private static final UUID SPACE = UUID.fromString("00000000-0000-7000-8000-000000000902");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsBeforeTheServletOrMultipartResolverCanReadTheBody() throws Exception {
        DocumentCreationAuthorization denied = (actor, space) -> {
            throw new AccessDeniedAsNotFoundException();
        };
        var filter = new UploadAuthorizationFilter(denied, new ObjectMapper());
        var request = multipartRequest();
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a", List.of()));

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> downstream.set(true));

        assertThat(downstream).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("NOT_FOUND");
    }

    @Test
    void permitsAnAuthorizedUploadWithoutInspectingRequestParameters() throws Exception {
        var filter = new UploadAuthorizationFilter(
                (actor, space) -> { }, new ObjectMapper());
        var request = multipartRequest();
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a", List.of()));

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> downstream.set(true));

        assertThat(downstream).isTrue();
    }

    @Test
    void matchesTheApplicationPathBehindANonRootServletContext() throws Exception {
        var filter = new UploadAuthorizationFilter(
                (actor, space) -> { throw new AccessDeniedAsNotFoundException(); },
                new ObjectMapper());
        var request = new MockHttpServletRequest(
                "POST", "/rag/api/v1/knowledge-spaces/" + SPACE + "/documents");
        request.setContextPath("/rag");
        request.setContentType("multipart/form-data; boundary=test");
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a", List.of()));

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> downstream.set(true));

        assertThat(downstream).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void rejectsAMissingSpaceEvenWhenTheRoleLayerAllowsTheActor() throws Exception {
        KnowledgeSpaceRepository missingSpaces = new KnowledgeSpaceRepository() {
            @Override public void insert(KnowledgeSpace space) { }
            @Override public Optional<KnowledgeSpace> findById(UUID spaceId) { return Optional.empty(); }
        };
        var filter = new UploadAuthorizationFilter(
                DocumentCreationAuthorization.backedBy(
                        missingSpaces, KnowledgeWriteAuthorization.allowAll()),
                new ObjectMapper());
        var request = multipartRequest();
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a", List.of()));

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> downstream.set(true));

        assertThat(downstream).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    private static MockHttpServletRequest multipartRequest() {
        var request = new MockHttpServletRequest("POST",
                "/api/v1/knowledge-spaces/" + SPACE + "/documents");
        request.setContentType("multipart/form-data; boundary=test");
        request.setAttribute(RequestTraceFilter.ATTRIBUTE_NAME,
                "018f25c8-5f7a-7abc-8def-0123456789ab");
        return request;
    }
}
