CREATE TABLE IF NOT EXISTS package_audit (
  id VARCHAR(36) PRIMARY KEY,
  admin_id VARCHAR(36) NOT NULL,
  package_id VARCHAR(64) NOT NULL,
  action VARCHAR(8) NOT NULL CHECK(action IN ('CREATE','UPDATE','DELETE')),
  previous_revision BIGINT,
  revision BIGINT NOT NULL,
  before_json LONGTEXT,
  after_json LONGTEXT,
  created_at BIGINT NOT NULL,
  CONSTRAINT package_audit_admin_fk FOREIGN KEY(admin_id) REFERENCES accounts(id),
  UNIQUE KEY package_audit_revision(package_id, revision),
  INDEX package_audit_page(created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
