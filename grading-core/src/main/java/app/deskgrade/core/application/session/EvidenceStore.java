package app.deskgrade.core.application.session;

import java.util.UUID;

@FunctionalInterface
public interface EvidenceStore {
  String save(UUID runId, UUID attemptId, SessionPlatform.Capture capture);
}
