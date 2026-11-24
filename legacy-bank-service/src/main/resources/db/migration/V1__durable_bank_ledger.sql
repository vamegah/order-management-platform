CREATE TABLE bank_account (
 account_number VARCHAR(40) PRIMARY KEY, currency VARCHAR(3) NOT NULL,
 balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0)
);
CREATE TABLE bank_transfer (
 request_id VARCHAR(160) PRIMARY KEY, request_hash VARCHAR(64) NOT NULL,
 transaction_id VARCHAR(40) UNIQUE, from_account VARCHAR(40), to_account VARCHAR(40),
 amount NUMERIC(19,2), currency VARCHAR(3), reference TEXT,
 outcome VARCHAR(40), completed_at TIMESTAMP WITH TIME ZONE, new_balance NUMERIC(19,2),
 reversed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE bank_nonce (nonce_hash VARCHAR(64) PRIMARY KEY, expires_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX bank_nonce_expiry ON bank_nonce(expires_at);
CREATE TABLE bank_simulator_control (
 id INTEGER PRIMARY KEY CHECK (id=1), mode VARCHAR(30) NOT NULL, delay_ms INTEGER NOT NULL,
 remaining INTEGER NOT NULL
);
INSERT INTO bank_simulator_control VALUES (1,'normal',0,0);
