package dev.christopherbell.report.model;

import java.util.Locale;

/** Allowlisted report categories used for storage and queue filtering. */
public enum ReportType {
  SPAM,
  HARASSMENT,
  VIOLENCE,
  SEXUAL,
  COPYRIGHT,
  OTHER;

  /** Maps the public reason code to a stable category without accepting arbitrary values. */
  public static ReportType fromReason(String reason) {
    if (reason == null) return OTHER;
    try {
      return valueOf(reason.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return OTHER;
    }
  }
}
