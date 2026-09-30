package com.yunjihai.rag.infrastructure.opensearch;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;

import java.io.IOException;
import java.util.Objects;

public record OpenSearchConnection(OpenSearchClient client, OpenSearchTransport transport)
        implements AutoCloseable {
    public OpenSearchConnection {
        Objects.requireNonNull(client);
        Objects.requireNonNull(transport);
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }
}
