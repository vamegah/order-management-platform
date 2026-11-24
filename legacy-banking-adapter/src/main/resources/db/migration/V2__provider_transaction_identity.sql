-- The frozen SOAP contract defines an opaque string, not a 40-character identifier.
ALTER TABLE adapter_transfer ALTER COLUMN transaction_id TYPE TEXT;
