-- V1__init_cache_schema.sql

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS semantic_cache (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    model VARCHAR(64) NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    prompt_text TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    response_text TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding 
    ON semantic_cache USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_semantic_cache_lookup 
    ON semantic_cache(tenant_id, model, expires_at);
