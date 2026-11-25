CREATE TABLE adapter_transfer (
 request_id VARCHAR(160) PRIMARY KEY, request_hash VARCHAR(64) NOT NULL,
 transaction_id VARCHAR(40), status VARCHAR(40), completed_at TIMESTAMP WITH TIME ZONE,
 new_balance NUMERIC(19,2), outcome VARCHAR(40), created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Transfer identities/results are never automatically expired: an old retry must not move money twice.
