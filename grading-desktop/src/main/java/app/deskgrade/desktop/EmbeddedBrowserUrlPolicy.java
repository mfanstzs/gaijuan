package app.deskgrade.desktop;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** Strict URL allow-list for the embedded browser. It never returns file or executable URLs. */
final class EmbeddedBrowserUrlPolicy {
  static final int MAX_URL_LENGTH = 2048;
  static final URI LOCAL_MOCK_URL = URI.create("http://127.0.0.1:8766/");

  record Target(URI uri, String origin, boolean localMock) {
    Target {
      Objects.requireNonNull(uri);
      Objects.requireNonNull(origin);
    }
  }

  private EmbeddedBrowserUrlPolicy() {}

  static Target parse(String value) {
    if (value == null) throw invalid("请输入网址。");
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) throw invalid("网址包含不允许的控制字符。");
    }
    String input = value.trim();
    if (input.isEmpty()) throw invalid("请输入网址。");
    if (input.length() > MAX_URL_LENGTH) throw invalid("网址过长，最多 2048 个字符。");
    for (int index = 0; index < input.length(); index++) {
      char character = input.charAt(index);
      if (Character.isISOControl(character)
          || Character.isWhitespace(character)
          || character == '\\') {
        throw invalid("网址包含不允许的字符。");
      }
    }

    String candidate = hasScheme(input) ? input : "https://" + input;
    final URI parsed;
    try {
      parsed = new URI(candidate);
    } catch (URISyntaxException malformed) {
      throw invalid("网址格式不正确。");
    }
    if (!parsed.isAbsolute() || parsed.isOpaque()) throw invalid("请输入完整网站地址。");

    String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) throw invalid("只允许打开 HTTPS 网站和本机测试页。");
    if (parsed.getRawUserInfo() != null) throw invalid("网址不能包含账号或密码。");
    if (parsed.getPort() == 0) throw invalid("网址端口无效。");
    if (parsed.getRawAuthority() != null && parsed.getRawAuthority().endsWith(":"))
      throw invalid("网址端口无效。");

    Authority authority = authority(parsed);
    int port = parsed.getPort() >= 0 ? parsed.getPort() : authority.inferredPort();
    if (port < -1 || port > 65_535) throw invalid("网址端口无效。");
    if (scheme.equals("https") && port == 443) port = -1;
    if (scheme.equals("http") && port == 80) port = -1;

    String path = parsed.getRawPath();
    if (path == null || path.isEmpty()) path = "/";
    String normalizedText =
        scheme
            + "://"
            + authority.renderHost()
            + (port < 0 ? "" : ":" + port)
            + path
            + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery())
            + (parsed.getRawFragment() == null ? "" : "#" + parsed.getRawFragment());
    final URI normalized;
    try {
      normalized = new URI(normalizedText).normalize();
    } catch (URISyntaxException malformed) {
      throw invalid("网址格式不正确。");
    }
    if (normalized.toASCIIString().length() > MAX_URL_LENGTH)
      throw invalid("规范化后的网址过长，最多 2048 个字符。");

    boolean localMock = LOCAL_MOCK_URL.equals(normalized);
    if (scheme.equals("http") && !localMock) throw invalid("普通网站必须使用 HTTPS；HTTP 只允许本机测试页。");
    String origin = scheme + "://" + authority.renderHost() + (port < 0 ? "" : ":" + port);
    return new Target(normalized, origin, localMock);
  }

  static boolean navigationAllowed(Target initial, String location) {
    if (initial == null || location == null || location.isBlank()) return false;
    final Target next;
    try {
      next = parse(location);
    } catch (IllegalArgumentException rejected) {
      return false;
    }
    return initial.localMock()
        ? initial.uri().equals(next.uri())
        : !next.localMock() && initial.origin().equals(next.origin());
  }

  private static boolean hasScheme(String input) {
    int colon = input.indexOf(':');
    if (colon <= 0) return false;
    if (!Character.isLetter(input.charAt(0))) return false;
    for (int index = 1; index < colon; index++) {
      char character = input.charAt(index);
      if (!Character.isLetterOrDigit(character)
          && character != '+'
          && character != '-'
          && character != '.') return false;
    }
    return true;
  }

  private static Authority authority(URI parsed) {
    String host = parsed.getHost();
    if (host != null && !host.isBlank()) {
      String normalized = host.toLowerCase(Locale.ROOT);
      if (normalized.startsWith("[") && normalized.endsWith("]"))
        normalized = normalized.substring(1, normalized.length() - 1);
      if (normalized.indexOf('%') >= 0) throw invalid("网址主机无效。");
      return new Authority(normalized, normalized.indexOf(':') >= 0, -1);
    }

    String raw = parsed.getRawAuthority();
    if (raw == null || raw.isBlank() || raw.indexOf('@') >= 0 || raw.indexOf('%') >= 0)
      throw invalid("网址主机无效。");
    String hostPart = raw;
    int colon = raw.lastIndexOf(':');
    if (colon >= 0) {
      String possiblePort = raw.substring(colon + 1);
      if (possiblePort.isEmpty() || !possiblePort.chars().allMatch(Character::isDigit))
        throw invalid("网址主机或端口无效。");
      hostPart = raw.substring(0, colon);
    }
    if (hostPart.isBlank() || hostPart.indexOf(':') >= 0) throw invalid("网址主机无效。");
    try {
      String ascii = IDN.toASCII(hostPart, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      if (ascii.isBlank()) throw invalid("网址主机无效。");
      int inferredPort = colon < 0 ? -1 : Integer.parseInt(raw.substring(colon + 1));
      return new Authority(ascii, false, inferredPort);
    } catch (IllegalArgumentException invalidHost) {
      throw invalid("网址主机无效。");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private record Authority(String host, boolean ipv6, int inferredPort) {
    String renderHost() {
      return ipv6 ? "[" + host + "]" : host;
    }
  }
}
