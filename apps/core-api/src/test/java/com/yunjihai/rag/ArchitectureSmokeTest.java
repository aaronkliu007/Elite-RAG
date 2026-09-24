package com.yunjihai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
class ArchitectureSmokeTest {

    @Test
    void applicationStarts() {
        // Spring 上下文能够启动即满足架构冒烟契约。
    }
}
