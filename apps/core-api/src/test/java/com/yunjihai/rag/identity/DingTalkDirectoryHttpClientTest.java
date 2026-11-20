package com.yunjihai.rag.identity;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class DingTalkDirectoryHttpClientTest {
    @Test void followsOfficialDepartmentHierarchyAndUserPagination() {
        var server = new WireMockServer(options().dynamicPort()); server.start();
        try {
            server.stubFor(post(urlEqualTo("/v1.0/oauth2/accessToken")).willReturn(okJson("{\"accessToken\":\"token\"}")));
            server.stubFor(post(urlPathEqualTo("/topapi/v2/department/get")).willReturn(okJson("{\"errcode\":0,\"result\":{\"dept_id\":1,\"name\":\"公司\"}}")));
            server.stubFor(post(urlPathEqualTo("/topapi/v2/department/listsub")).withRequestBody(matchingJsonPath("$.dept_id", equalTo("1"))).willReturn(okJson("{\"errcode\":0,\"result\":[{\"dept_id\":2,\"parent_id\":1,\"name\":\"研发\"}]}")));
            server.stubFor(post(urlPathEqualTo("/topapi/v2/department/listsub")).withRequestBody(matchingJsonPath("$.dept_id", equalTo("2"))).willReturn(okJson("{\"errcode\":0,\"result\":[]}")));
            server.stubFor(post(urlPathEqualTo("/topapi/v2/user/list")).withRequestBody(matchingJsonPath("$.cursor", equalTo("0"))).willReturn(okJson("{\"errcode\":0,\"result\":{\"has_more\":true,\"next_cursor\":1,\"list\":[{\"userid\":\"u1\",\"name\":\"张三\",\"active\":false,\"dept_id_list\":[2]}]}}")));
            server.stubFor(post(urlPathEqualTo("/topapi/v2/user/list")).withRequestBody(matchingJsonPath("$.cursor", equalTo("1"))).willReturn(okJson("{\"errcode\":0,\"result\":{\"has_more\":false,\"list\":[]}}")));
            var adapter = new DingTalkDirectoryHttpClient(URI.create(server.baseUrl()), URI.create(server.baseUrl()), new DingTalkCredentials("id", "secret"), Duration.ofSeconds(2));
            assertThat(adapter.listDepartments()).extracting(ExternalDepartment::externalId).containsExactly("1", "2");
            assertThat(adapter.listUsers("2")).singleElement().satisfies(user -> { assertThat(user.externalId()).isEqualTo("u1"); assertThat(user.active()).isTrue(); });
        } finally { server.stop(); }
    }
}
