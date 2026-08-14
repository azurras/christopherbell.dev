package dev.christopherbell.libs.moderation;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validated moderation audit boundary that excludes secrets and content bodies. */
public record ModerationAuditCommand(
    String eventId,
    String actorAccountId,
    String actorUsername,
    String action,
    String targetType,
    String targetId,
    String targetLabel,
    String reason,
    String message,
    Map<String, String> beforeValues,
    Map<String, String> afterValues,
    Map<String, String> metadata) {

  private static final int MAX_REASON_LENGTH = 500;
  private static final int MAX_VALUE_LENGTH = 100;
  private static final int MAX_MAP_ENTRIES = 10;
  private static final Set<String> STATE_KEYS = Set.of("role", "status", "resolution");
  private static final Set<String> METADATA_KEYS = Set.of(
      "source", "reportId", "postId", "accountId", "username", "resolution");

  /** Creates an immutable command after validating every externally supplied partition. */
  public static ModerationAuditCommand create(
      String actorAccountId,
      String actorUsername,
      String action,
      String targetType,
      String targetId,
      String targetLabel,
      String reason,
      String message,
      Map<String, String> beforeValues,
      Map<String, String> afterValues,
      Map<String, String> metadata
  ) throws InvalidRequestException {
    requireText(actorAccountId, 128, "actor account id");
    requireText(actorUsername, 128, "actor username");
    requireText(action, 64, "action");
    requireText(targetType, 32, "target type");
    requireText(targetId, 128, "target id");
    requireText(targetLabel, 128, "target label");
    requireText(message, 256, "message");
    requireText(reason, MAX_REASON_LENGTH, "reason");
    var before = validateMap(beforeValues, STATE_KEYS, "before state");
    var after = validateMap(afterValues, STATE_KEYS, "after state");
    var safeMetadata = validateMap(metadata, METADATA_KEYS, "metadata");
    return new ModerationAuditCommand(
        UUID.randomUUID().toString(),
        actorAccountId, actorUsername,
        action, targetType, targetId, targetLabel,
        ModerationReasonRedactor.redact(reason.strip()), message,
        before, after, safeMetadata);
  }

  private static Map<String, String> validateMap(
      Map<String, String> values,
      Set<String> allowedKeys,
      String label
  ) throws InvalidRequestException {
    var safe = values == null ? Map.<String, String>of() : values;
    if (safe.size() > MAX_MAP_ENTRIES) {
      throw invalid(label);
    }
    for (var entry : safe.entrySet()) {
      if (!allowedKeys.contains(entry.getKey()) || entry.getValue() == null
          || entry.getValue().length() > MAX_VALUE_LENGTH) {
        throw invalid(label);
      }
    }
    return Map.copyOf(safe);
  }

  private static void requireText(String value, int max, String label)
      throws InvalidRequestException {
    if (value == null || value.isBlank() || value.strip().length() > max) {
      throw invalid(label);
    }
  }

  private static InvalidRequestException invalid(String label) {
    return new InvalidRequestException("Invalid moderation audit " + label + ".");
  }
}
