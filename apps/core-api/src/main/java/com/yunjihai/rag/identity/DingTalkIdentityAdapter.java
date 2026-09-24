package com.yunjihai.rag.identity;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DingTalkIdentityAdapter implements DingTalkClient {

    private static final int PAGE_SIZE = 100;

    private final URI baseUri;
    private final DingTalkCredentials credentials;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper json;

    public DingTalkIdentityAdapter(URI baseUri, DingTalkCredentials credentials, Duration timeout) {
        if (baseUri == null || credentials == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("钉钉客户端配置不完整");
        }
        this.baseUri = baseUri;
        this.credentials = credentials;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.json = new ObjectMapper();
    }

    @Override
    public ExternalIdentity exchangeAuthorizationCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("授权码不能为空");
        }
        var request = new LinkedHashMap<String, String>();
        request.put("clientId", credentials.clientId());
        request.put("clientSecret", credentials.clientSecret());
        request.put("code", code);
        request.put("grantType", "authorization_code");
        var token = requiredText(post("/v1.0/oauth2/userAccessToken", request, null), "accessToken");
        var user = get("/v1.0/contact/users/me", token);
        return new ExternalIdentity(
                requiredText(user, "userId"),
                requiredText(user, "nick"),
                requiredBoolean(user, "active"));
    }

    @Override
    public List<ExternalDepartment> listDepartments() {
        var token = applicationAccessToken();
        var result = new ArrayList<ExternalDepartment>();
        var cursor = "0";
        var seenCursors = new HashSet<String>();
        seenCursors.add(cursor);
        for (var pageNumber = 0; pageNumber < 1_000; pageNumber++) {
            var page = get("/v1.0/contact/departments?cursor=" + encode(cursor) + "&size=" + PAGE_SIZE, token);
            for (var item : requiredArray(page, "items")) {
                result.add(new ExternalDepartment(
                        requiredText(item, "deptId"),
                        optionalText(item, "parentId"),
                        requiredText(item, "name")));
            }
            cursor = nextCursor(page);
            if (cursor == null) {
                return List.copyOf(result);
            }
            if (!seenCursors.add(cursor)) {
                throw new IncompleteOrganizationSnapshotException("钉钉分页游标重复");
            }
        }
        throw new IncompleteOrganizationSnapshotException("钉钉组织分页超过安全上限");
    }

    @Override
    public List<ExternalUser> listUsers(String departmentExternalId) {
        if (departmentExternalId == null || departmentExternalId.isBlank()) {
            throw new IllegalArgumentException("部门 externalId 不能为空");
        }
        var token = applicationAccessToken();
        var result = new ArrayList<ExternalUser>();
        var cursor = "0";
        var seenCursors = new HashSet<String>();
        seenCursors.add(cursor);
        for (var pageNumber = 0; pageNumber < 1_000; pageNumber++) {
            var path = "/v1.0/contact/users?departmentId=" + encode(departmentExternalId)
                    + "&cursor=" + encode(cursor) + "&size=" + PAGE_SIZE;
            var page = get(path, token);
            for (var item : requiredArray(page, "items")) {
                result.add(new ExternalUser(
                        requiredText(item, "userId"),
                        requiredText(item, "name"),
                        requiredBoolean(item, "active"),
                        textSet(item, "departmentIds")));
            }
            cursor = nextCursor(page);
            if (cursor == null) {
                return List.copyOf(result);
            }
            if (!seenCursors.add(cursor)) {
                throw new IncompleteOrganizationSnapshotException("钉钉分页游标重复");
            }
        }
        throw new IncompleteOrganizationSnapshotException("钉钉用户分页超过安全上限");
    }

    private String applicationAccessToken() {
        return requiredText(post(
                "/v1.0/oauth2/accessToken",
                Map.of("appKey", credentials.clientId(), "appSecret", credentials.clientSecret()),
                null), "accessToken");
    }

    private JsonNode post(String path, Object body, String accessToken) {
        try {
            var builder = HttpRequest.newBuilder(resolve(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            addToken(builder, accessToken);
            return send(builder.build());
        } catch (JacksonException exception) {
            throw invalidResponse(exception);
        }
    }

    private JsonNode get(String path, String accessToken) {
        var builder = HttpRequest.newBuilder(resolve(path)).timeout(timeout).GET();
        addToken(builder, accessToken);
        return send(builder.build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IdentityProviderException(
                        IdentityProviderFailureKind.UPSTREAM_ERROR,
                        "钉钉接口返回非成功状态：" + response.statusCode());
            }
            var root = json.readTree(response.body());
            if (root == null || !root.isObject()) {
                throw new IdentityProviderException(
                        IdentityProviderFailureKind.INVALID_RESPONSE,
                        "钉钉接口响应不是 JSON 对象");
            }
            return root;
        } catch (HttpTimeoutException exception) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.TIMEOUT,
                    "钉钉接口调用超时",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.INTERRUPTED,
                    "钉钉接口调用被中断",
                    exception);
        } catch (JacksonException exception) {
            throw invalidResponse(exception);
        } catch (IOException exception) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.UPSTREAM_ERROR,
                    "钉钉接口调用失败",
                    exception);
        }
    }

    private String nextCursor(JsonNode page) {
        var hasMoreNode = page.get("hasMore");
        if (hasMoreNode == null || !hasMoreNode.isBoolean()) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.INVALID_RESPONSE,
                    "钉钉分页响应缺少布尔字段 hasMore");
        }
        if (!hasMoreNode.booleanValue()) {
            return null;
        }
        var next = page.get("nextToken");
        if (next == null || !next.isString() || next.stringValue().isBlank()) {
            throw new IncompleteOrganizationSnapshotException("钉钉分页未完成且缺少下一页游标");
        }
        return next.stringValue();
    }

    private static Iterable<JsonNode> requiredArray(JsonNode object, String field) {
        var node = object.get(field);
        if (node == null || !node.isArray()) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.INVALID_RESPONSE,
                    "钉钉响应字段类型错误：" + field);
        }
        return node;
    }

    private static String requiredText(JsonNode object, String field) {
        var node = object.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.INVALID_RESPONSE,
                    "钉钉响应字段类型错误：" + field);
        }
        return node.stringValue();
    }

    private static String optionalText(JsonNode object, String field) {
        var node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.INVALID_RESPONSE,
                    "钉钉响应字段类型错误：" + field);
        }
        return node.stringValue();
    }

    private static boolean requiredBoolean(JsonNode object, String field) {
        var node = object.get(field);
        if (node == null || !node.isBoolean()) {
            throw new IdentityProviderException(
                    IdentityProviderFailureKind.INVALID_RESPONSE,
                    "钉钉响应字段类型错误：" + field);
        }
        return node.booleanValue();
    }

    private static Set<String> textSet(JsonNode object, String field) {
        var values = new java.util.LinkedHashSet<String>();
        for (var node : requiredArray(object, field)) {
            if (!node.isString() || node.stringValue().isBlank()) {
                throw new IdentityProviderException(
                        IdentityProviderFailureKind.INVALID_RESPONSE,
                        "钉钉响应数组字段类型错误：" + field);
            }
            values.add(node.stringValue());
        }
        return Set.copyOf(values);
    }

    private static void addToken(HttpRequest.Builder builder, String accessToken) {
        if (accessToken != null) {
            builder.header("x-acs-dingtalk-access-token", accessToken);
        }
    }

    private URI resolve(String path) {
        var base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static IdentityProviderException invalidResponse(Exception cause) {
        return new IdentityProviderException(
                IdentityProviderFailureKind.INVALID_RESPONSE,
                "钉钉接口响应无法解析",
                cause);
    }
}
