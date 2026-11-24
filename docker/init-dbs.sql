-- Initialize isolated databases for Orvix microservices
CREATE DATABASE billing_db;
CREATE DATABASE guardrail_db;
CREATE DATABASE cache_db;

-- Connect to cache_db and enable pgvector
\c cache_db;
CREATE EXTENSION IF NOT EXISTS vector;
