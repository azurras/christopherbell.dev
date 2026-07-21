package dev.christopherbell.sharedfolder.audit;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Bounded, persistence-ready audit data that never accepts exception text or absolute paths.
 *
 * <p>Action, outcome, and failure category are constrained tokens so a later persistence sink
 * cannot accidentally turn this contract into an unbounded log-message channel.
 */
public record SharedFolderAuditCommand(
    String accountId,
    String action,
    String relativePathOrResourceId,
    Instant occurredAt,
    String clientIp,
    Long size,
    String outcome,
    String failureCategory) {
  private static final int MAX_ACCOUNT_ID_LENGTH = 128;
  private static final int MAX_ACTION_LENGTH = 64;
  private static final int MAX_PATH_OR_RESOURCE_ID_LENGTH = 512;
  private static final int MAX_CLIENT_IP_LENGTH = 64;
  private static final int MAX_OUTCOME_LENGTH = 64;
  private static final int MAX_FAILURE_CATEGORY_LENGTH = 64;

  /** Validates every field before an audit command can cross the persistence boundary. */
  public SharedFolderAuditCommand {
    accountId = requiredToken(accountId, "account id", MAX_ACCOUNT_ID_LENGTH);
    action = requiredToken(action, "action", MAX_ACTION_LENGTH);
    relativePathOrResourceId = validatedResource(relativePathOrResourceId);
    if (occurredAt == null) {
      throw new IllegalArgumentException("Audit timestamp is required");
    }
    clientIp = requiredClientIp(clientIp);
    if (size != null && size < 0) {
      throw new IllegalArgumentException("Audit size cannot be negative");
    }
    outcome = requiredToken(outcome, "outcome", MAX_OUTCOME_LENGTH);
    if (failureCategory != null) {
      failureCategory = requiredToken(
          failureCategory, "failure category", MAX_FAILURE_CATEGORY_LENGTH);
    }
  }

  static String validatedResource(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_PATH_OR_RESOURCE_ID_LENGTH
        || containsControlCharacter(value) || value.startsWith("/") || value.startsWith("\\")
        || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || containsEncodedSeparator(value)) {
      throw new IllegalArgumentException("Audit path or resource id is unsafe");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
          || segment.endsWith(".") || segment.endsWith(" ")) {
        throw new IllegalArgumentException("Audit path or resource id is unsafe");
      }
    }
    return value;
  }

  static String boundedResource(String value) {
    String candidate = value == null || value.isEmpty() ? "root" : value;
    try {
      return validatedResource(candidate);
    } catch (IllegalArgumentException exception) {
      try {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(candidate.getBytes(StandardCharsets.UTF_8));
        return "resource-sha256-" + HexFormat.of().formatHex(digest);
      } catch (NoSuchAlgorithmException impossible) {
        return "resource-unavailable";
      }
    }
  }

  private static String requiredClientIp(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_CLIENT_IP_LENGTH
        || containsControlCharacter(value)) {
      throw new IllegalArgumentException("Audit client IP is invalid");
    }
    return value;
  }

  private static String requiredToken(String value, String label, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength || containsControlCharacter(value)
        || !value.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Audit " + label + " is invalid");
    }
    return value;
  }

  private static boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static boolean containsEncodedSeparator(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return normalized.contains("%2f") || normalized.contains("%5c");
  }
}
