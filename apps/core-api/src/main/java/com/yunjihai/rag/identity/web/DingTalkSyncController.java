package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.identity.DingTalkOrganizationSynchronizer;
import com.yunjihai.rag.identity.OrganizationSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/dingtalk")
@Profile("local")
public class DingTalkSyncController {
    private final ObjectProvider<DingTalkOrganizationSynchronizer> synchronizer;
    private final RoleAssignmentService roles;
    private final boolean scopeConfirmed;
    private final JdbcClient jdbc;
    public DingTalkSyncController(ObjectProvider<DingTalkOrganizationSynchronizer> synchronizer, RoleAssignmentService roles, JdbcClient jdbc,
            @Value("${rag.dingtalk.authoritative-scope-confirmed:false}") boolean scopeConfirmed) {
        this.synchronizer = synchronizer; this.roles = roles; this.jdbc = jdbc; this.scopeConfirmed = scopeConfirmed;
    }
    @GetMapping public Status status(Authentication authentication) {
        requireAdmin(authentication); return new Status(synchronizer.getIfAvailable() != null, scopeConfirmed);
    }
    @GetMapping("/departments") public List<Department> departments(Authentication authentication) {
        requireAdmin(authentication);
        return jdbc.sql("SELECT id, parent_id, name FROM org_unit WHERE enabled AND external_id LIKE 'dingtalk:dept:%' ORDER BY name, id")
                .query((row, number) -> new Department(row.getObject("id", UUID.class), row.getObject("parent_id", UUID.class), row.getString("name"))).list();
    }
    @PostMapping("/preview") public ResponseEntity<Summary> preview(Authentication authentication) {
        requireAdmin(authentication);
        var sync = synchronizer.getIfAvailable();
        return sync == null ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build() : ResponseEntity.ok(summary(sync.preview()));
    }
    @PostMapping("/sync") public ResponseEntity<Summary> synchronize(Authentication authentication) {
        requireAdmin(authentication);
        if (!scopeConfirmed) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        var sync = synchronizer.getIfAvailable();
        return sync == null ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build() : ResponseEntity.ok(summary(sync.synchronize()));
    }
    private void requireAdmin(Authentication authentication) {
        try {
            UUID id = UUID.fromString(authentication.getName());
            if (!roles.hasSystemRole(id, SystemRole.SYSTEM_ADMIN)) throw new AccessDeniedException("无管理权限");
        } catch (NullPointerException | IllegalArgumentException failure) { throw new AccessDeniedException("无管理权限"); }
    }
    private static Summary summary(OrganizationSnapshot snapshot) {
        return new Summary(snapshot.departments().size(), snapshot.users().size(), snapshot.capturedAt());
    }
    public record Status(boolean configured, boolean authoritativeScopeConfirmed) { }
    public record Summary(int departmentCount, int userCount, Instant capturedAt) { }
    public record Department(UUID id, UUID parentId, String name) { }
}
