-- Requests sent to each LLM provider per UTC day (ADR-036). Numbers only: no prompt, no answer, no client, no listing.
-- The row is the shared daily limit: the client reserves a request with one INSERT .. ON CONFLICT .. WHERE requests < limit.
CREATE TABLE llm_usage (
    usage_day     DATE        NOT NULL,
    provider_id   VARCHAR(64) NOT NULL,
    requests      INTEGER     NOT NULL DEFAULT 0 CHECK (requests >= 0),
    input_tokens  BIGINT      NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT      NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    PRIMARY KEY (usage_day, provider_id)
);
