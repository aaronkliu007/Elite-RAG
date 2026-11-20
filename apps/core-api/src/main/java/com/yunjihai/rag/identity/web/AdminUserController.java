package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@Profile("local")
public class AdminUserController {
    private final UserAdministrationService users;
    private final RoleAssignmentService roles;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public AdminUserController(
            UserAdministrationService users,
            RoleAssignmentService roles,
            IdempotencyService idempotency,
            ObjectMapper json) {
        this.users = Objects.requireNonNull(users);
        this.roles = Objects.requireNonNull(roles);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.json = Objects.requireNonNull(json);
    }

    @GetMapping
    public List<UserView> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID actorId = requireSystemAdmin(authentication);
        PageBounds bounds = PageBounds.of(page, pageSize);
        return users.listUsers().stream()
                .sorted(java.util.Comparator.comparing(UserAccount::username))
                .skip(bounds.offset())
                .limit(pageSize)
                .map(UserView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateUserRequest request) {
        UUID actorId = requireSystemAdmin(authentication);
        var validatedRequest = requireRequest(request);
        requirePassword(validatedRequest.password());
        try {
            String hash = hash(validatedRequest);
            return idempotency.execute(
                    new IdempotencyScope(actorId, "admin.user.create", idempotencyKey),
                    hash,
                    UserView.class,
                    () -> UserView.from(users.createUser(
                            validatedRequest.username(), validatedRequest.displayName(),
                            validatedRequest.password(), actorId)));
        } finally {
            Arrays.fill(validatedRequest.password(), '\0');
        }
    }

    @PatchMapping("/{userId}/enabled")
    public OperationView setEnabled(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EnabledRequest request) {
        UUID actorId = requireSystemAdmin(authentication);
        var validatedRequest = requireRequest(request);
        String hash = hash(List.of(userId, validatedRequest));
        return idempotency.execute(
                new IdempotencyScope(actorId, "admin.user.enabled", idempotencyKey),
                hash,
                OperationView.class,
                () -> {
                    users.setEnabled(userId, validatedRequest.enabled(), actorId);
                    return new OperationView(userId, "UPDATED");
                });
    }

    @PostMapping("/{userId}/reset-password")
    public OperationView resetPassword(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResetPasswordRequest request) {
        UUID actorId = requireSystemAdmin(authentication);
        var validatedRequest = requireRequest(request);
        requirePassword(validatedRequest.password());
        try {
            String hash = hash(List.of(userId, validatedRequest));
            return idempotency.execute(
                    new IdempotencyScope(actorId, "admin.user.reset-password", idempotencyKey),
                    hash,
                    OperationView.class,
                    () -> {
                        users.resetPassword(userId, validatedRequest.password(), actorId);
                        return new OperationView(userId, "PASSWORD_RESET");
                    });
        } finally {
            Arrays.fill(validatedRequest.password(), '\0');
        }
    }

    private UUID requireSystemAdmin(Authentication authentication) {
        UUID actorId = userId(authentication);
        if (!roles.hasSystemRole(actorId, SystemRole.SYSTEM_ADMIN)) {
            throw new AccessDeniedException("system administrator required");
        }
        return actorId;
    }

    private String hash(Object request) {
        return IdempotencyService.sha256(json.writeValueAsBytes(request));
    }

    private static <T> T requireRequest(T request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return request;
    }

    private static void requirePassword(char[] password) {
        if (password == null) throw new IllegalArgumentException("password is required");
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedException("authentication required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedException("invalid authentication");
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

    public record CreateUserRequest(String username, String displayName, char[] password) { }
    public record EnabledRequest(boolean enabled) { }
    public record ResetPasswordRequest(char[] password) { }
    public record OperationView(UUID userId, String status) { }

    public record UserView(
            UUID id,
            String username,
            String displayName,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        static UserView from(UserAccount account) {
            return new UserView(
                    account.id(), account.username(), account.displayName(), account.enabled(),
                    account.createdAt(), account.updatedAt());
        }
    }
}
