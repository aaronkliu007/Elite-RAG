package com.yunjihai.rag.identity;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DingTalkIdentityAdapterTest {

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("授权码只能通过配置凭据换取身份")
    void exchangesAuthorizationCodeWithConfiguredCredentials() {
        wireMock.stubFor(post("/v1.0/oauth2/userAccessToken")
                .withRequestBody(equalTo("{\"clientId\":\"app-key\",\"clientSecret\":\"app-secret\",\"code\":\"auth-code\",\"grantType\":\"authorization_code\"}"))
                .willReturn(json("{\"accessToken\":\"user-token\"}")));
        wireMock.stubFor(get("/v1.0/contact/users/me")
                .withHeader("x-acs-dingtalk-access-token", equalTo("user-token"))
                .willReturn(json("{\"userId\":\"u-1\",\"nick\":\"张三\",\"active\":true}")));

        var adapter = adapter(Duration.ofSeconds(1));

        assertThat(adapter.exchangeAuthorizationCode("auth-code"))
                .isEqualTo(new ExternalIdentity("u-1", "张三", true));
    }

    @Test
    @DisplayName("钉钉超时被转换为明确的上游超时错误")
    void reportsTimeoutWithoutLeakingCredentials() {
        wireMock.stubFor(post("/v1.0/oauth2/userAccessToken")
                .willReturn(json("{\"accessToken\":\"late-token\"}").withFixedDelay(500)));

        assertThatThrownBy(() -> adapter(Duration.ofMillis(80)).exchangeAuthorizationCode("auth-code"))
                .isInstanceOf(IdentityProviderException.class)
                .extracting("kind")
                .isEqualTo(IdentityProviderFailureKind.TIMEOUT);
    }

    @Test
    @DisplayName("上游字段类型错误时拒绝创建身份")
    void rejectsWrongResponseTypes() {
        wireMock.stubFor(post("/v1.0/oauth2/userAccessToken")
                .willReturn(json("{\"accessToken\":\"user-token\"}")));
        wireMock.stubFor(get("/v1.0/contact/users/me")
                .willReturn(json("{\"userId\":123,\"nick\":\"张三\",\"active\":true}")));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(1)).exchangeAuthorizationCode("auth-code"))
                .isInstanceOf(IdentityProviderException.class)
                .extracting("kind")
                .isEqualTo(IdentityProviderFailureKind.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("分页响应声称还有数据却缺少游标时拒绝返回不完整组织")
    void rejectsIncompletePagination() {
        wireMock.stubFor(post("/v1.0/oauth2/accessToken")
                .willReturn(json("{\"accessToken\":\"app-token\"}")));
        wireMock.stubFor(get(urlEqualTo("/v1.0/contact/departments?cursor=0&size=100"))
                .willReturn(json("{\"items\":[],\"hasMore\":true}")));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(1)).listDepartments())
                .isInstanceOf(IncompleteOrganizationSnapshotException.class);
    }

    @Test
    @DisplayName("分页游标重复时立即中止，避免无限循环")
    void rejectsRepeatedPaginationCursor() {
        wireMock.stubFor(post("/v1.0/oauth2/accessToken")
                .willReturn(json("{\"accessToken\":\"app-token\"}")));
        wireMock.stubFor(get(urlEqualTo("/v1.0/contact/departments?cursor=0&size=100"))
                .inScenario("cursor-loop")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("repeated")
                .willReturn(json("{\"items\":[],\"hasMore\":true,\"nextToken\":\"same\"}")));
        wireMock.stubFor(get(urlEqualTo("/v1.0/contact/departments?cursor=same&size=100"))
                .inScenario("cursor-loop")
                .whenScenarioStateIs("repeated")
                .willReturn(json("{\"items\":[],\"hasMore\":true,\"nextToken\":\"same\"}")));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(1)).listDepartments())
                .isInstanceOf(IncompleteOrganizationSnapshotException.class)
                .hasMessageContaining("游标");
    }

    @Test
    @DisplayName("凭据对象的日志文本永不包含密钥")
    void redactsSecretFromDiagnosticText() {
        var credentials = new DingTalkCredentials("app-key", "highly-sensitive-secret");

        assertThat(credentials.toString())
                .contains("app-key", "<redacted>")
                .doesNotContain("highly-sensitive-secret");
    }

    private DingTalkIdentityAdapter adapter(Duration timeout) {
        return new DingTalkIdentityAdapter(
                URI.create(wireMock.baseUrl()),
                new DingTalkCredentials("app-key", "app-secret"),
                timeout);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
