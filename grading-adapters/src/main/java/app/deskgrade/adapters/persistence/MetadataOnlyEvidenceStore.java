package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.EvidenceStore;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** New captures stay in request memory only. This class has no filesystem dependency. */
public final class MetadataOnlyEvidenceStore implements EvidenceStore {
  public static final String NO_IMAGE_STORED = "NO_IMAGE_STORED";

  @Override
  public String save(UUID runId, UUID attemptId, SessionPlatform.Capture capture) {
    try {
      if (runId == null || attemptId == null || capture == null)
        throw new IllegalArgumentException();
      byte[] png = capture.png();
      if (png.length == 0
          || !HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(png))
              .equals(capture.sha256())) throw new IllegalArgumentException();
      return NO_IMAGE_STORED;
    } catch (Exception error) {
      throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    }
  }
}
