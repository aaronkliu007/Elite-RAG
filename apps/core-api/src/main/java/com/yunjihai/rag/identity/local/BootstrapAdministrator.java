package com.yunjihai.rag.identity.local;

import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SystemRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

public class BootstrapAdministrator implements ApplicationRunner {
    private final UserAdministrationService users;
    private final RoleAssignmentService roles;
    private final BootstrapAdminProperties properties;
    private final Clock clock;

    public BootstrapAdministrator(
            UserAdministrationService users,
            RoleAssignmentService roles,
            BootstrapAdminProperties properties,
            Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.roles = Objects.requireNonNull(roles);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        initialize();
    }

    public void initialize() {
        if (roles.hasAnySystemRole(SystemRole.SYSTEM_ADMIN)) {
            return;
        }
        if (!properties.complete()) {
            throw new IllegalStateException("缺少引导管理员配置，且系统中不存在系统管理员");
        }
        char[] password = properties.password().toCharArray();
        var account = users.createUser(
                properties.username(), properties.displayName(), password,
                com.yunjihai.rag.common.UuidV7.generate(clock.instant()));
        roles.grantSystemRole(
                account.id(), SystemRole.SYSTEM_ADMIN, account.id(), clock.instant());
    }
}
