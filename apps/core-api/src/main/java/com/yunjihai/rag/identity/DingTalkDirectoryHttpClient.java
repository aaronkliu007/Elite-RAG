package com.yunjihai.rag.identity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** DingTalk enterprise directory API. Credentials and upstream payloads are never logged. */
public final class DingTalkDirectoryHttpClient implements DingTalkClient {
    private final URI oauthBase; private final URI directoryBase; private final DingTalkCredentials credentials;
    private final Duration timeout; private final HttpClient http; private final ObjectMapper json = new ObjectMapper();
    public DingTalkDirectoryHttpClient(URI oauthBase, URI directoryBase, DingTalkCredentials credentials, Duration timeout) {
        this.oauthBase = Objects.requireNonNull(oauthBase); this.directoryBase = Objects.requireNonNull(directoryBase);
        this.credentials = Objects.requireNonNull(credentials); this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("超时必须为正数");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public ExternalIdentity exchangeAuthorizationCode(String code) { throw new UnsupportedOperationException("通讯录客户端不处理登录"); }
    @Override public List<ExternalDepartment> listDepartments() {
        String token = token();
        JsonNode root = result(callDirectory("/topapi/v2/department/get", Map.of("dept_id", 1), token));
        var result = new ArrayList<ExternalDepartment>();
        result.add(new ExternalDepartment(id(root, "dept_id"), null, text(root, "name")));
        var queue = new ArrayDeque<String>(); queue.add(result.getFirst().externalId());
        var seen = new HashSet<String>(); seen.add(queue.peek());
        while (!queue.isEmpty()) {
            String parent = queue.remove();
            JsonNode children = result(callDirectory("/topapi/v2/department/listsub", Map.of("dept_id", number(parent)), token));
            if (!children.isArray()) throw invalid();
            for (JsonNode child : children) {
                String childId = id(child, "dept_id");
                if (!id(child, "parent_id").equals(parent) || !seen.add(childId) || seen.size() > 10000) throw invalid();
                result.add(new ExternalDepartment(childId, parent, text(child, "name"))); queue.add(childId);
            }
        }
        return List.copyOf(result);
    }
    @Override public List<ExternalUser> listUsers(String departmentExternalId) {
        long department = number(departmentExternalId); String token = token(); long cursor = 0;
        var seen = new HashSet<Long>(); var users = new ArrayList<ExternalUser>();
        for (int count = 0; count < 1000; count++) {
            if (!seen.add(cursor)) throw new IncompleteOrganizationSnapshotException("钉钉用户分页游标重复");
            JsonNode page = result(callDirectory("/topapi/v2/user/list", Map.of("dept_id", department, "cursor", cursor, "size", 100), token));
            JsonNode list = page.get("list"), more = page.get("has_more");
            if (list == null || !list.isArray() || more == null || !more.isBoolean()) throw invalid();
            for (JsonNode item : list) {
                var ids = new LinkedHashSet<String>(); JsonNode deptIds = item.get("dept_id_list");
                if (deptIds == null || !deptIds.isArray()) throw invalid();
                for (JsonNode deptId : deptIds) { if (!deptId.isIntegralNumber()) throw invalid(); ids.add(Long.toString(deptId.longValue())); }
                JsonNode disabled = item.get("disable_status");
                if (disabled != null && !disabled.isBoolean()) throw invalid();
                users.add(new ExternalUser(text(item, "userid"), text(item, "name"), disabled == null || !disabled.booleanValue(), Set.copyOf(ids)));
            }
            if (!more.booleanValue()) return List.copyOf(users);
            JsonNode next = page.get("next_cursor");
            if (next == null || !next.isIntegralNumber() || next.longValue() < 0) throw invalid();
            cursor = next.longValue();
        }
        throw new IncompleteOrganizationSnapshotException("钉钉用户分页超过安全上限");
    }
    private String token() { return text(post(oauthBase, "/v1.0/oauth2/accessToken", Map.of("appKey", credentials.clientId(), "appSecret", credentials.clientSecret())), "accessToken"); }
    private JsonNode callDirectory(String path, Object body, String token) {
        return post(directoryBase, path + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), body);
    }
    private JsonNode post(URI base, String path, Object body) {
        try {
            String prefix = base.toString().replaceAll("/+$", "");
            var request = HttpRequest.newBuilder(URI.create(prefix + path)).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IdentityProviderException(IdentityProviderFailureKind.UPSTREAM_ERROR, "钉钉接口返回非成功状态");
            JsonNode node = json.readTree(response.body()); if (node == null || !node.isObject()) throw invalid(); return node;
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IdentityProviderException(IdentityProviderFailureKind.INTERRUPTED, "钉钉接口调用中断"); }
        catch (IdentityProviderException failure) { throw failure; }
        catch (Exception failure) { throw new IdentityProviderException(IdentityProviderFailureKind.UPSTREAM_ERROR, "钉钉接口调用失败"); }
    }
    private static JsonNode result(JsonNode response) { JsonNode code = response.get("errcode"); JsonNode result = response.get("result"); if (code == null || !code.isIntegralNumber() || code.longValue() != 0 || result == null) throw invalid(); return result; }
    private static String text(JsonNode node, String key) { JsonNode value = node.get(key); if (value == null || !value.isString() || value.stringValue().isBlank()) throw invalid(); return value.stringValue(); }
    private static String id(JsonNode node, String key) { JsonNode value = node.get(key); if (value == null || !value.isIntegralNumber() || value.longValue() <= 0) throw invalid(); return Long.toString(value.longValue()); }
    private static long number(String value) { try { long number = Long.parseLong(value); if (number <= 0) throw invalid(); return number; } catch (NumberFormatException failure) { throw invalid(); } }
    private static IdentityProviderException invalid() { return new IdentityProviderException(IdentityProviderFailureKind.INVALID_RESPONSE, "钉钉通讯录响应无效或不完整"); }
}
