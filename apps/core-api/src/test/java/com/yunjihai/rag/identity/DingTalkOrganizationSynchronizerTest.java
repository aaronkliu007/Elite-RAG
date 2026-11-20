package com.yunjihai.rag.identity;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DingTalkOrganizationSynchronizerTest {
    @Test void neverAppliesWhenScopeIsUnconfirmedOrCollectionFails() {
        var writes = new AtomicInteger();
        DingTalkClient client = new DingTalkClient() {
            public ExternalIdentity exchangeAuthorizationCode(String code) { throw new UnsupportedOperationException(); }
            public List<ExternalDepartment> listDepartments() { return List.of(new ExternalDepartment("1", null, "公司")); }
            public List<ExternalUser> listUsers(String id) { return List.of(new ExternalUser("u", "张三", true, Set.of("1"))); }
        };
        var collector = new DingTalkOrganizationCollector(client, Clock.systemUTC());
        var sync = new DingTalkOrganizationSynchronizer(collector, snapshot -> writes.incrementAndGet(), false);
        assertThat(sync.preview().users()).hasSize(1);
        assertThatThrownBy(sync::synchronize).isInstanceOf(IllegalStateException.class);
        assertThat(writes).hasValue(0);
        new DingTalkOrganizationSynchronizer(collector, snapshot -> writes.incrementAndGet(), true).synchronize();
        assertThat(writes).hasValue(1);
    }
}
