package dev.christopherbell.libs.path;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Filesystem-free grammar for slash-separated paths that remain unambiguous on Windows. */
public final class WindowsSafeRelativePath {
  private static final Set<String> RESERVED_DOS_NAMES = Set.of(
      "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
      "COM8", "COM9", "COM\u00b9", "COM\u00b2", "COM\u00b3", "LPT1", "LPT2", "LPT3", "LPT4",
      "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT\u00b9", "LPT\u00b2", "LPT\u00b3");

  private WindowsSafeRelativePath() {}

  /** Returns validated components without consulting or resolving against a filesystem. */
  public static List<String> segments(String value, boolean allowEmpty) {
    if (value == null) {
      throw invalid("Path is required.");
    }
    if (value.isEmpty()) {
      if (allowEmpty) {
        return List.of();
      }
      throw invalid("Path is required.");
    }
    if (value.startsWith("/") || value.startsWith("\\")
        || value.matches("(?i)^[a-z]:.*")) {
      throw invalid("Path must be relative.");
    }
    if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
        || containsControlCharacter(value) || containsEncodedSeparator(value)) {
      throw invalid("Path contains an unsafe Windows form.");
    }

    String[] segments = value.split("/", -1);
    for (String segment : segments) {
      requireSegment(segment);
    }
    return List.of(segments);
  }

  /** Validates one Windows-safe name with no path separator. */
  public static void requireSingleSegment(String value) {
    if (value == null || value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
      throw invalid("Name must be one path segment.");
    }
    requireSegment(value);
  }

  private static void requireSegment(String segment) {
    if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
      throw invalid("Dot and empty path segments are not allowed.");
    }
    if (segment.endsWith(".") || segment.endsWith(" ") || containsControlCharacter(segment)
        || segment.indexOf(':') >= 0 || containsEncodedSeparator(segment)
        || containsWindowsForbiddenCharacter(segment)) {
      throw invalid("Path segment is not a safe Windows name.");
    }
    int extension = segment.indexOf('.');
    String baseName = segment.substring(0, extension >= 0 ? extension : segment.length())
        .toUpperCase(Locale.ROOT);
    if (RESERVED_DOS_NAMES.contains(baseName)) {
      throw invalid("Path segment uses a reserved DOS device name.");
    }
  }

  private static boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static boolean containsEncodedSeparator(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return normalized.contains("%2f") || normalized.contains("%5c");
  }

  private static boolean containsWindowsForbiddenCharacter(String value) {
    return value.indexOf('"') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
        || value.indexOf('|') >= 0 || value.indexOf('?') >= 0 || value.indexOf('*') >= 0;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
