package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;

import app.deskgrade.adapters.commercial.GatewayException;
import app.deskgrade.core.domain.model.ModelCallException;
import org.junit.jupiter.api.Test;

class DeskGradeShellPrivacyTest {
  @Test
  void localModelFailuresGiveBoundedOwnKeyGuidanceWithoutRawProviderDetails() {
    for (var kind : ModelCallException.Kind.values()) {
      String message = DeskGradeShell.safeError(new ModelCallException(kind, 401));
      assertFalse(message.isBlank());
      assertFalse(message.contains(kind.name()), kind.name());
      assertFalse(message.matches("(?s).*(https?://|sk-|登录|注册账号|刷新账号|管理员).*"), message);
    }
  }

  @Test
  void ordinaryErrorsNeverDisplayInternalCodesOrDirectUsersToRemovedAccounts() {
    for (var code : GatewayException.Code.values()) {
      String message = DeskGradeShell.safeError(new GatewayException(code));
      assertFalse(message.contains(code.name()), code.name());
      assertFalse(message.matches("(?s).*(?:https?://|[A-Za-z]:\\\\).*"), message);
      assertFalse(message.matches("(?s).*(?:登录|注册|剩余次数|刷新账号).*"), message);
    }
  }
}
