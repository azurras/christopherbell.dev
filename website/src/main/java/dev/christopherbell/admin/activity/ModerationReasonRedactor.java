package dev.christopherbell.admin.activity;

import java.util.regex.Pattern;

/** Removes common credential, identity, and content-body shapes from audit reasons. */
final class ModerationReasonRedactor {
  private static final String REDACTED = "[REDACTED]";
  private static final Pattern EMAIL = Pattern.compile(
      "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])");
  private static final Pattern AUTHORIZATION = Pattern.compile(
      "(?i)\\bauthorization\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+");
  private static final Pattern NAMED_VALUE = Pattern.compile(
      "(?i)\\b(password|passphrase|api[_-]?key|secret|token|body|content|post[_-]?body)"
          + "\\b\\s*[:=]\\s*.*?(?=\\s+\\b[A-Za-z][A-Za-z0-9_-]*\\b\\s*[:=]|[,;]|$)");
  private static final Pattern JWT = Pattern.compile(
      "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
          + "(?![A-Za-z0-9_-])");

  private ModerationReasonRedactor() {}

  static String redact(String value) {
    var redacted = AUTHORIZATION.matcher(value).replaceAll("authorization=" + REDACTED);
    redacted = NAMED_VALUE.matcher(redacted).replaceAll("$1=" + REDACTED);
    redacted = EMAIL.matcher(redacted).replaceAll(REDACTED);
    return JWT.matcher(redacted).replaceAll(REDACTED);
  }
}
