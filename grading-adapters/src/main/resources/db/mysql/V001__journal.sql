CREATE TABLE IF NOT EXISTS journal_config_document (
 id VARCHAR(150) PRIMARY KEY, kind VARCHAR(40) NOT NULL, revision BIGINT NOT NULL,
 json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS journal_grading_run (
 run_id CHAR(36) PRIMARY KEY, target_identity VARCHAR(500) NOT NULL, config_snapshot LONGTEXT NOT NULL,
 state VARCHAR(40) NOT NULL, pause_reason VARCHAR(40) NOT NULL,
 started_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 owner_scope CHAR(64) NOT NULL, client_scope CHAR(64) NOT NULL DEFAULT '', INDEX journal_run_owner_time(owner_scope,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS journal_grading_attempt (
 attempt_id CHAR(36) PRIMARY KEY, run_id CHAR(36) NOT NULL, ordinal INT NOT NULL,
 answer_id VARCHAR(200) NOT NULL, question_id VARCHAR(200) NOT NULL, page_revision BIGINT NOT NULL,
 action_epoch BIGINT NOT NULL, rule_revision BIGINT NOT NULL, model_revision BIGINT NOT NULL,
 platform_revision BIGINT NOT NULL, config_snapshot LONGTEXT NOT NULL,
 evidence_path VARCHAR(2000) NOT NULL, capture_sha256 CHAR(64) NOT NULL,
 captured_at TIMESTAMP(6) NOT NULL, state VARCHAR(40) NOT NULL, score DECIMAL(32,8), result_json LONGTEXT,
 submit_prepared BOOLEAN DEFAULT FALSE NOT NULL, submit_sent BOOLEAN DEFAULT FALSE NOT NULL,
 confirmed_at TIMESTAMP(6), owner_scope CHAR(64) NOT NULL, question_snapshot LONGTEXT,
 accepted_request_id CHAR(36), score_readback DECIMAL(32,8), score_readback_at TIMESTAMP(6),
 FOREIGN KEY (run_id) REFERENCES journal_grading_run(run_id), UNIQUE(run_id,ordinal),
 INDEX journal_attempt_owner_time(owner_scope,captured_at,attempt_id),
 CHECK (ordinal > 0 AND page_revision >= 0 AND action_epoch >= 0),
 CHECK (rule_revision > 0 AND model_revision > 0 AND platform_revision > 0),
 CHECK (score IS NULL OR score >= 0),
 CHECK (submit_prepared OR (submit_sent=FALSE AND confirmed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS journal_model_call (
 call_id CHAR(36) PRIMARY KEY, run_id CHAR(36) NOT NULL, attempt_id CHAR(36) NOT NULL,
 profile_id VARCHAR(200) NOT NULL, started_at TIMESTAMP(6) NOT NULL, completed_at TIMESTAMP(6),
 source VARCHAR(20), input_tokens BIGINT, output_tokens BIGINT, estimated_cost DECIMAL(38,18),
 elapsed_ms BIGINT, error_code VARCHAR(100), response_request_id CHAR(36),
 response_completed_at TIMESTAMP(6), result_json LONGTEXT, validation_status VARCHAR(40), java_total DECIMAL(32,8),
 FOREIGN KEY (run_id) REFERENCES journal_grading_run(run_id),
 FOREIGN KEY (attempt_id) REFERENCES journal_grading_attempt(attempt_id),
 CHECK (input_tokens IS NULL OR input_tokens >= 0), CHECK (output_tokens IS NULL OR output_tokens >= 0),
 CHECK (estimated_cost IS NULL OR estimated_cost >= 0), CHECK (elapsed_ms IS NULL OR elapsed_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS journal_run_event (
 event_id CHAR(36) PRIMARY KEY, run_id CHAR(36) NOT NULL, attempt_id CHAR(36),
 event_type VARCHAR(40) NOT NULL, reason VARCHAR(40) NOT NULL, happened_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY (run_id) REFERENCES journal_grading_run(run_id), INDEX journal_event_attempt(attempt_id,happened_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS journal_legacy_import (
 run_id CHAR(36) PRIMARY KEY, source_digest CHAR(64) NOT NULL, imported_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY (run_id) REFERENCES journal_grading_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
