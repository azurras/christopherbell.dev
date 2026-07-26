package dev.christopherbell.report;

import dev.christopherbell.report.model.ReportTargetType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic privacy-safe key for one reporter and target partition. */
public final class ReportOpenDedupeKey {
  private ReportOpenDedupeKey() {}

  /** Hashes the full reporter-target identity used by the sparse unique index. */
  public static String forTarget(
      String reporterAccountId,
      ReportTargetType targetType,
      String targetId
  ) {
    try {
      var value = String.join("\n", reporterAccountId, targetType.name(), targetId);
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
