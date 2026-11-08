CREATE TABLE IF NOT EXISTS config_document (
  id VARCHAR(150) PRIMARY KEY, kind VARCHAR(40) NOT NULL, revision BIGINT NOT NULL,
  json CLOB NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS grading_run (
  run_id UUID PRIMARY KEY, target_identity VARCHAR(500) NOT NULL, config_snapshot CLOB NOT NULL,
  state VARCHAR(40) NOT NULL, pause_reason VARCHAR(40) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS grading_attempt (
  attempt_id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES grading_run(run_id), ordinal INT NOT NULL,
  answer_id VARCHAR(200) NOT NULL, question_id VARCHAR(200) NOT NULL, page_revision BIGINT NOT NULL,
  action_epoch BIGINT NOT NULL, rule_revision BIGINT NOT NULL, model_revision BIGINT NOT NULL,
  platform_revision BIGINT NOT NULL, config_snapshot CLOB NOT NULL,
  evidence_path VARCHAR(2000) NOT NULL, capture_sha256 CHAR(64) NOT NULL,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL, state VARCHAR(40) NOT NULL,
  score DECIMAL(32,8), result_json CLOB,
  submit_prepared BOOLEAN DEFAULT FALSE NOT NULL, submit_sent BOOLEAN DEFAULT FALSE NOT NULL,
  confirmed_at TIMESTAMP WITH TIME ZONE,
  CHECK (ordinal > 0 AND page_revision >= 0 AND action_epoch >= 0),
  CHECK (rule_revision > 0 AND model_revision > 0 AND platform_revision > 0),
  CHECK (score IS NULL OR score >= 0),
  CHECK (submit_prepared OR (submit_sent=FALSE AND confirmed_at IS NULL)),
  UNIQUE(run_id,ordinal)
);
CREATE TABLE IF NOT EXISTS model_call (
  call_id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES grading_run(run_id),
  attempt_id UUID NOT NULL REFERENCES grading_attempt(attempt_id), profile_id VARCHAR(200) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE,
  source VARCHAR(20), input_tokens BIGINT, output_tokens BIGINT, estimated_cost DECIMAL(38,18),
  elapsed_ms BIGINT, error_code VARCHAR(100),
  CHECK (input_tokens IS NULL OR input_tokens >= 0),
  CHECK (output_tokens IS NULL OR output_tokens >= 0),
  CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
  CHECK (elapsed_ms IS NULL OR elapsed_ms >= 0)
);
CREATE TABLE IF NOT EXISTS run_event (
  event_id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES grading_run(run_id),
  attempt_id UUID, event_type VARCHAR(40) NOT NULL, reason VARCHAR(40) NOT NULL,
  happened_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS attempt_run_idx ON grading_attempt(run_id);
CREATE INDEX IF NOT EXISTS call_run_idx ON model_call(run_id);
