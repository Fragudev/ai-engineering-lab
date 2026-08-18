-- The embedding dimension (1024, bge-m3) is a schema-level commitment, not just an app setting.
-- See docs/adr/0003-persistence-and-vector-store.md.
CREATE EXTENSION IF NOT EXISTS vector;
