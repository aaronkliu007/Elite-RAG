package com.yunjihai.rag.infrastructure.opensearch;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

public final class OpenSearchClientFactory {
    public OpenSearchConnection create(OpenSearchProperties properties) {
        int port = properties.endpoint().getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(properties.endpoint().getScheme()) ? 443 : 80;
        }
        var host = new HttpHost(properties.endpoint().getScheme(), properties.endpoint().getHost(), port);
        var builder = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper());
        String path = properties.endpoint().getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            builder.setPathPrefix(path);
        }
        var requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis()))
                .build();
        BasicCredentialsProvider credentials = properties.authenticated()
                ? new BasicCredentialsProvider() : null;
        if (credentials != null) {
            credentials.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(
                    properties.username(), properties.password().toCharArray()));
        }
        builder.setHttpClientConfigCallback(client -> {
            client.setDefaultRequestConfig(requestConfig);
            if (credentials != null) client.setDefaultCredentialsProvider(credentials);
            return client;
        });
        var transport = builder.build();
        return new OpenSearchConnection(new OpenSearchClient(transport), transport);
    }
}
