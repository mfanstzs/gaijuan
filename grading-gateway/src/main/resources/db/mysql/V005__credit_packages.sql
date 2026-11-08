CREATE TABLE IF NOT EXISTS credit_packages (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  credits BIGINT NOT NULL CHECK(credits > 0),
  price_minor BIGINT CHECK(price_minor IS NULL OR price_minor >= 0),
  currency CHAR(3) NOT NULL,
  enabled BOOLEAN NOT NULL,
  revision BIGINT NOT NULL CHECK(revision > 0),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  deleted_at BIGINT,
  INDEX credit_packages_catalog(deleted_at, enabled, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
