#!/bin/sh
set -eu

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
: "${RAG_MINIO_ACCESS_KEY:?RAG_MINIO_ACCESS_KEY is required}"
: "${RAG_MINIO_SECRET_KEY:?RAG_MINIO_SECRET_KEY is required}"
: "${RAG_MINIO_BUCKET:?RAG_MINIO_BUCKET is required}"

if [ "$RAG_MINIO_BUCKET" != 'rag-documents' ]; then
    echo 'The local application policy is scoped to rag-documents' >&2
    exit 1
fi
if [ "$RAG_MINIO_ACCESS_KEY" = "$MINIO_ROOT_USER" ]; then
    echo 'The application access key must differ from the root user' >&2
    exit 1
fi

mc alias set bootstrap http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "bootstrap/$RAG_MINIO_BUCKET" >/dev/null
mc admin user add bootstrap "$RAG_MINIO_ACCESS_KEY" "$RAG_MINIO_SECRET_KEY" >/dev/null
mc admin policy create bootstrap rag-documents-app-rw /minio-init/rag-documents-app-policy.json >/dev/null
mc admin policy attach bootstrap rag-documents-app-rw --user "$RAG_MINIO_ACCESS_KEY" >/dev/null

# Confirm the issued application credentials can enumerate only their configured bucket.
mc alias set app http://minio:9000 "$RAG_MINIO_ACCESS_KEY" "$RAG_MINIO_SECRET_KEY" >/dev/null
mc ls "app/$RAG_MINIO_BUCKET" >/dev/null
