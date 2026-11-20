package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.knowledge.KnowledgeSpaceView;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-spaces")
@ConditionalOnBean({
        KnowledgeSpaceService.class,
        RoleAssignmentService.class,
        UserAdministrationService.class,
        IdempotencyService.class
})
public class KnowledgeSpaceController {
    private final KnowledgeSpaceService spaces;
    private final RoleAssignmentService roles;
    private final UserAdministrationService users;
    private final KnowledgeWriteAuthorization authorization;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public KnowledgeSpaceController(
            KnowledgeSpaceService spaces,
            RoleAssignmentService roles,
            UserAdministrationService users,
            KnowledgeWriteAuthorization authorization,
            IdempotencyService idempotency,
            ObjectMapper json,
            Clock clock) {
        this.spaces = Objects.requireNonNull(spaces);
        this.roles = Objects.requireNonNull(roles);
        this.users = Objects.requireNonNull(users);
        this.authorization = Objects.requireNonNull(authorization);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @GetMapping
    public List<KnowledgeSpaceView> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID actorId = userId(authentication);
        return spaces.listAuthorized(actorId, page, pageSize).stream()
                .map(space -> view(space, actorId))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeSpaceView create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateSpaceRequest request) {
        UUID actorId = userId(authentication);
        var validatedRequest = requireRequest(request);
        String hash = hash(validatedRequest);
        return idempotency.execute(
                new IdempotencyScope(actorId, "knowledge-space.create", idempotencyKey),
                hash,
                KnowledgeSpaceView.class,
                () -> view(spaces.createSpace(
                        validatedRequest.name(), validatedRequest.description(), actorId, clock.instant()), actorId));
    }

    @GetMapping("/{spaceId}/members")
    public List<MemberView> listMembers(
            Authentication authentication,
            @PathVariable UUID spaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID actorId = userId(authentication);
        requireManageMembers(actorId, spaceId);
        PageBounds bounds = PageBounds.of(page, pageSize);
        return users.listUsers().stream()
                .map(user -> member(user, spaceId))
                .filter(member -> !member.roles().isEmpty())
                .sorted(java.util.Comparator.comparing(MemberView::username))
                .skip(bounds.offset())
                .limit(pageSize)
                .toList();
    }

    @GetMapping("/{spaceId}/member-candidates")
    public List<MemberCandidate> searchMemberCandidates(
            Authentication authentication, @PathVariable UUID spaceId,
            @RequestParam String q, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireManageMembers(userId(authentication), spaceId);
        PageBounds bounds = PageBounds.of(page, pageSize);
        if (q == null || q.isBlank() || q.strip().length() > 128) {
            throw new IllegalArgumentException("请输入有效的用户搜索条件");
        }
        String term = q.strip().toLowerCase(java.util.Locale.ROOT);
        return users.listUsers().stream()
                .filter(UserAccount::enabled)
                .filter(user -> user.username().toLowerCase(java.util.Locale.ROOT).contains(term)
                        || user.displayName().toLowerCase(java.util.Locale.ROOT).contains(term))
                .sorted(java.util.Comparator.comparing(UserAccount::username))
                .skip(bounds.offset()).limit(pageSize)
                .map(user -> new MemberCandidate(user.id(), user.username(), user.displayName()))
                .toList();
    }

    public record MemberCandidate(UUID userId, String username, String displayName) { }

    @PostMapping("/{spaceId}/members")
    public MemberView grantMember(
            Authentication authentication,
            @PathVariable UUID spaceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MemberRoleRequest request) {
        UUID actorId = userId(authentication);
        var validatedRequest = requireRequest(request);
        requireMemberRole(validatedRequest);
        UserAccount target = findUser(validatedRequest.userId());
        String hash = hash(List.of(spaceId, validatedRequest));
        return idempotency.execute(
                new IdempotencyScope(actorId, "knowledge-space.member.grant", idempotencyKey),
                hash,
                MemberView.class,
                () -> {
                    roles.grantSpaceRole(
                            actorId, spaceId, validatedRequest.userId(), validatedRequest.role(), clock.instant());
                    return member(target, spaceId);
                });
    }

    @DeleteMapping("/{spaceId}/members/{userId}/roles/{role}")
    public MemberView revokeMember(
            Authentication authentication,
            @PathVariable UUID spaceId,
            @PathVariable UUID userId,
            @PathVariable SpaceRole role,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID actorId = userId(authentication);
        UserAccount target = findUser(userId);
        String hash = hash(List.of(spaceId, userId, role));
        return idempotency.execute(
                new IdempotencyScope(actorId, "knowledge-space.member.revoke", idempotencyKey),
                hash,
                MemberView.class,
                () -> {
                    roles.revokeSpaceRole(actorId, spaceId, userId, role, clock.instant());
                    return member(target, spaceId);
                });
    }

    private void requireManageMembers(UUID actorId, UUID spaceId) {
        authorization.require(actorId, spaceId, null, KnowledgeWriteAuthorization.Action.MANAGE_ACL);
    }

    private UserAccount findUser(UUID userId) {
        return users.listUsers().stream()
                .filter(user -> user.id().equals(userId))
                .findFirst()
                .orElseThrow(AccessDeniedAsNotFoundException::new);
    }

    private MemberView member(UserAccount user, UUID spaceId) {
        return new MemberView(
                user.id(), user.username(), user.displayName(), user.enabled(),
                roles.activeSpaceRoles(user.id(), spaceId));
    }

    private KnowledgeSpaceView view(KnowledgeSpace space, UUID actorId) {
        return new KnowledgeSpaceView(
                space.id(), space.name(), space.description(), space.revision(),
                space.createdBy(), space.createdAt(), allowedActions(actorId, space.id()));
    }

    private Set<String> allowedActions(UUID actorId, UUID spaceId) {
        boolean systemAdmin = roles.hasSystemRole(actorId, SystemRole.SYSTEM_ADMIN);
        Set<SpaceRole> assigned = roles.activeSpaceRoles(actorId, spaceId);
        var actions = new LinkedHashSet<String>();
        actions.add("VIEW");
        actions.add("SEARCH");
        actions.add("DOWNLOAD");
        if (systemAdmin || assigned.contains(SpaceRole.SPACE_ADMIN)) {
            actions.addAll(List.of(
                    "EDIT_SPACE", "MANAGE_ACL", "CREATE_DOCUMENT", "EDIT_DOCUMENT",
                    "SUBMIT_FOR_REVIEW", "REVIEW_DOCUMENT", "PUBLISH_DOCUMENT", "ARCHIVE_DOCUMENT"));
        } else {
            if (assigned.contains(SpaceRole.EDITOR)) {
                actions.addAll(List.of("CREATE_DOCUMENT", "EDIT_DOCUMENT", "SUBMIT_FOR_REVIEW"));
            }
            if (assigned.contains(SpaceRole.REVIEWER_PUBLISHER)) {
                actions.addAll(List.of("REVIEW_DOCUMENT", "PUBLISH_DOCUMENT"));
            }
        }
        return Set.copyOf(actions);
    }

    private String hash(Object request) {
        return IdempotencyService.sha256(json.writeValueAsBytes(request));
    }

    private static <T> T requireRequest(T request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return request;
    }

    private static void requireMemberRole(MemberRoleRequest request) {
        if (request.userId() == null) throw new IllegalArgumentException("userId is required");
        if (request.role() == null) throw new IllegalArgumentException("role is required");
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedAsNotFoundException();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedAsNotFoundException();
        }
    }

    private record PageBounds(long offset) {
        static PageBounds of(int page, int pageSize) {
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("page must be >= 1 and pageSize must be between 1 and 100");
            }
            return new PageBounds((long) (page - 1) * pageSize);
        }
    }

    public record CreateSpaceRequest(String name, String description) { }
    public record MemberRoleRequest(UUID userId, SpaceRole role) { }
    public record MemberView(
            UUID userId,
            String username,
            String displayName,
            boolean enabled,
            Set<SpaceRole> roles) {
        public MemberView { roles = Set.copyOf(roles); }
    }
}
