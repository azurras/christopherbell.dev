package dev.christopherbell.admin.commandcenter.logs;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Reads a bounded, redacted window from the single log file configured by the application. */
@Service
public class CommandCenterLogService {

  private static final int MAX_QUERY_LENGTH = 100;
  private static final Pattern LEVEL_PATTERN =
      Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|ERROR)\\b");
  private static final Pattern AUTHORIZATION_PATTERN =
      Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+");
  private static final Pattern STRUCTURED_QUOTED_SECRET_PATTERN = Pattern.compile(
      "(?i)(?<![A-Za-z0-9_-])(\\\"?(?:password|api[_-]?key|secret|token|authorization)\\\"?\\s*[:=]\\s*)(['\\\"])");
  private static final Pattern NAMED_SECRET_PATTERN =
      Pattern.compile("(?i)(password|api[_-]?key|secret|token)(\\s*[:=]\\s*)[^\\s,;]+");
  private static final Pattern JWT_PATTERN =
      Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

  private final CommandCenterProperties properties;

  /** Creates a reader whose file path and bounds come only from trusted application properties. */
  public CommandCenterLogService(CommandCenterProperties properties) {
    this.properties = properties;
  }

  /**
   * Returns recent log records after validating the opaque cursor and bounded filter inputs.
   * Client inputs can filter the configured file but can never choose a path or regular expression.
   */
  public LogPage read(String cursor, String requestedLevel, String literalQuery) {
    LogLevel level = LogLevel.parse(requestedLevel);
    String query = normalizeLiteralQuery(literalQuery);
    CursorResult cursorResult = decodeCursor(cursor);
    Path logPath = properties.getLogPath();

    if (!Files.isRegularFile(logPath)) {
      return new LogPage("", List.of(), cursorResult.provided(), "missing");
    }

    try {
      return readFile(logPath, cursorResult, level, query);
    } catch (IOException exception) {
      return new LogPage("", List.of(), cursorResult.provided(), "unavailable");
    }
  }

  private LogPage readFile(
      Path logPath, CursorResult cursorResult, LogLevel requestedLevel, String query)
      throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(logPath, BasicFileAttributes.class);
    long fileSize = attributes.size();
    Cursor cursor = cursorResult.cursor();
    boolean reset = cursorResult.invalid();
    boolean cursorMatches =
        cursor != null
            && cursor.offset() <= fileSize
            && hasUnambiguousIdentity(attributes, cursor.offset())
            && cursor.fingerprint().equals(fingerprint(logPath, attributes, cursor.offset()));

    long start;
    if (cursorMatches) {
      start = cursor.offset();
    } else {
      reset = reset || cursor != null;
      start = Math.max(0, fileSize - maxBytes());
    }

    ReadWindow window =
        readWindow(
            logPath,
            start,
            fileSize,
            cursorMatches,
            cursorMatches && cursor.discarding());
    List<LogRecord> records = filterAndBound(window.lines(), requestedLevel, query);
    String nextCursor =
        encodeCursor(
            new Cursor(
                fingerprint(logPath, attributes, window.nextOffset()),
                window.nextOffset(),
                window.discarding()));
    return new LogPage(nextCursor, records, reset, "ok");
  }

  private ReadWindow readWindow(
      Path logPath,
      long start,
      long fileSize,
      boolean incremental,
      boolean cursorDiscarding)
      throws IOException {
    int length = (int) Math.min(maxBytes(), fileSize - start);
    byte[] bytes = new byte[length];
    try (var file = new RandomAccessFile(logPath.toFile(), "r")) {
      file.seek(start);
      file.readFully(bytes);
    }

    int from = 0;
    boolean discarding = cursorDiscarding || (!incremental && start > 0);
    if (discarding) {
      int newline = firstNewline(bytes);
      if (newline < 0) {
        return new ReadWindow(List.of(), start + bytes.length, true);
      }
      from = newline + 1;
      discarding = false;
    }

    SplitResult split = splitCompleteLines(bytes, from, start);
    if (!split.hasIncompleteLine()) {
      return new ReadWindow(split.lines(), start + bytes.length, false);
    }

    // Hold a normal partial line at its start so the next poll can redact it as one unit. Once a
    // line fills an entire window without a newline, advance in discard mode until its terminator.
    int incompleteLength = bytes.length - split.incompleteStart();
    if (split.incompleteStart() == from && incompleteLength == maxBytes()) {
      return new ReadWindow(split.lines(), start + bytes.length, true);
    }
    return new ReadWindow(split.lines(), start + split.incompleteStart(), false);
  }

  private List<LogRecord> filterAndBound(
      List<RawLine> lines, LogLevel requestedLevel, String query) {
    List<LogRecord> matches = new ArrayList<>();
    for (RawLine line : lines) {
      String redacted = redact(new String(line.bytes(), UTF_8));
      LogLevel actualLevel = classify(redacted);
      if ((requestedLevel == null || requestedLevel == actualLevel)
          && (query == null || redacted.toLowerCase(Locale.ROOT).contains(query))) {
        matches.add(
            new LogRecord(
                line.offset(), actualLevel == null ? "" : actualLevel.name(), redacted));
      }
    }

    List<LogRecord> boundedNewestFirst = new ArrayList<>();
    int renderedBytes = 0;
    for (int index = matches.size() - 1;
        index >= 0 && boundedNewestFirst.size() < maxLines();
        index--) {
      LogRecord record = matches.get(index);
      int recordBytes = (record.text() + "\n").getBytes(UTF_8).length;
      if (recordBytes > maxBytes() - renderedBytes) {
        continue;
      }
      boundedNewestFirst.add(record);
      renderedBytes += recordBytes;
    }
    Collections.reverse(boundedNewestFirst);
    return List.copyOf(boundedNewestFirst);
  }

  private SplitResult splitCompleteLines(byte[] bytes, int from, long absoluteStart) {
    List<RawLine> lines = new ArrayList<>();
    int lineStart = from;
    for (int index = from; index < bytes.length; index++) {
      if (bytes[index] != '\n') {
        continue;
      }
      int lineEnd = index;
      if (lineEnd > lineStart && bytes[lineEnd - 1] == '\r') {
        lineEnd--;
      }
      if (lineEnd > lineStart) {
        byte[] line = java.util.Arrays.copyOfRange(bytes, lineStart, lineEnd);
        lines.add(new RawLine(absoluteStart + lineStart, line));
      }
      lineStart = index + 1;
    }
    return new SplitResult(lines, lineStart, lineStart < bytes.length);
  }

  private int firstNewline(byte[] bytes) {
    for (int index = 0; index < bytes.length; index++) {
      if (bytes[index] == '\n') {
        return index;
      }
    }
    return -1;
  }

  private String redact(String text) {
    String redacted = redactStructuredQuotedSecrets(text);
    redacted = redactStructuredQuotedSecrets(normalizeEscapedStructuralQuotes(redacted));
    redacted = AUTHORIZATION_PATTERN.matcher(redacted).replaceAll("$1[REDACTED]");
    redacted = NAMED_SECRET_PATTERN.matcher(redacted).replaceAll("$1$2[REDACTED]");
    return JWT_PATTERN.matcher(redacted).replaceAll("[REDACTED]");
  }

  private static String normalizeEscapedStructuralQuotes(String text) {
    var normalized = new StringBuilder(text.length());
    for (int index = 0; index < text.length();) {
      if (text.charAt(index) != '\\') {
        normalized.append(text.charAt(index++));
        continue;
      }
      int start = index;
      while (index < text.length() && text.charAt(index) == '\\') index++;
      int slashCount = index - start;
      if (slashCount == 1 && index < text.length()
          && (text.charAt(index) == '"' || text.charAt(index) == '\'')) {
        normalized.append(text.charAt(index++));
      } else {
        normalized.append(text, start, index);
      }
    }
    return normalized.toString();
  }

  private String redactStructuredQuotedSecrets(String text) {
    var output = new StringBuilder(text.length());
    var matcher = STRUCTURED_QUOTED_SECRET_PATTERN.matcher(text);
    int cursor = 0;
    while (matcher.find(cursor)) {
      char quote = matcher.group(2).charAt(0);
      int closing = closingQuote(text, matcher.end(), quote);
      output.append(text, cursor, matcher.end()).append("[REDACTED]");
      if (closing < 0) {
        return output.toString();
      }
      cursor = closing;
    }
    return output.append(text, cursor, text.length()).toString();
  }

  private static int closingQuote(String text, int from, char quote) {
    boolean escaped = false;
    for (int index = from; index < text.length(); index++) {
      char current = text.charAt(index);
      if (current == '\\') {
        escaped = !escaped;
      } else {
        if (current == quote && !escaped) return index;
        escaped = false;
      }
    }
    return -1;
  }

  private LogLevel classify(String text) {
    var matcher = LEVEL_PATTERN.matcher(text);
    return matcher.find() ? LogLevel.valueOf(matcher.group(1)) : null;
  }

  private String normalizeLiteralQuery(String query) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    if (query.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException("Log query must not exceed 100 characters");
    }
    return query.toLowerCase(Locale.ROOT);
  }

  private CursorResult decodeCursor(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return new CursorResult(null, false, false);
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(encoded), UTF_8);
      String[] parts = decoded.split(":", -1);
      if (parts.length != 4 || !parts[0].equals("v2")) {
        throw new IllegalArgumentException("Unsupported cursor");
      }
      long offset = Long.parseLong(parts[2]);
      boolean discarding = switch (parts[3]) {
        case "0" -> false;
        case "1" -> true;
        default -> throw new IllegalArgumentException("Invalid cursor state");
      };
      if (parts[1].isBlank() || offset < 0) {
        throw new IllegalArgumentException("Invalid cursor");
      }
      return new CursorResult(new Cursor(parts[1], offset, discarding), true, false);
    } catch (IllegalArgumentException exception) {
      return new CursorResult(null, true, true);
    }
  }

  private String encodeCursor(Cursor cursor) {
    String value =
        "v2:"
            + cursor.fingerprint()
            + ":"
            + cursor.offset()
            + ":"
            + (cursor.discarding() ? "1" : "0");
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(UTF_8));
  }

  private String fingerprint(Path path, BasicFileAttributes attributes, long offset)
      throws IOException {
    try {
      MessageDigest digestBuilder = MessageDigest.getInstance("SHA-256");
      digestBuilder.update("command-center-log-v2\0".getBytes(UTF_8));
      Object fileKey = attributes.fileKey();
      digestBuilder.update((fileKey == null ? "no-file-key" : fileKey.toString()).getBytes(UTF_8));
      digestBuilder.update((byte) 0);
      digestBuilder.update(attributes.creationTime().toString().getBytes(UTF_8));
      digestBuilder.update(ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
      updateContentFingerprint(digestBuilder, path, offset);
      byte[] digest = digestBuilder.digest();
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for log cursors", exception);
    }
  }

  private boolean hasUnambiguousIdentity(BasicFileAttributes attributes, long offset) {
    // Empty files have no content fallback. Without a platform file key, reset rather than claim
    // that an offset-zero cursor still refers to the same empty file.
    return attributes.fileKey() != null || offset > 0;
  }

  private void updateContentFingerprint(MessageDigest digest, Path path, long offset)
      throws IOException {
    int budget = (int) Math.min(8_192, Math.min(offset, maxBytes()));
    if (budget == 0) {
      return;
    }
    int prefixLength = Math.min(budget, (budget + 1) / 2);
    int suffixLength = Math.min(budget - prefixLength, (int) offset - prefixLength);
    try (var file = new RandomAccessFile(path.toFile(), "r")) {
      byte[] prefix = new byte[prefixLength];
      file.readFully(prefix);
      digest.update(prefix);
      if (suffixLength > 0) {
        byte[] suffix = new byte[suffixLength];
        file.seek(offset - suffixLength);
        file.readFully(suffix);
        digest.update(suffix);
      }
    }
  }

  private int maxLines() {
    return Math.max(1, properties.getMaxLogLines());
  }

  private int maxBytes() {
    return Math.max(1, properties.getMaxLogBytes());
  }

  /** Plain-text log page contract returned by the command-center API layer. */
  public record LogPage(String nextCursor, List<LogRecord> records, boolean reset, String status) {}

  /** One redacted plain-text log line with its byte-offset sequence. */
  public record LogRecord(long sequence, String level, String text) {}

  private record Cursor(String fingerprint, long offset, boolean discarding) {}

  private record CursorResult(Cursor cursor, boolean provided, boolean invalid) {}

  private record RawLine(long offset, byte[] bytes) {}

  private record SplitResult(
      List<RawLine> lines, int incompleteStart, boolean hasIncompleteLine) {}

  private record ReadWindow(List<RawLine> lines, long nextOffset, boolean discarding) {}

  private enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    private static LogLevel parse(String value) {
      if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
        return null;
      }
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(
            "Log level must be ALL, TRACE, DEBUG, INFO, WARN, or ERROR", exception);
      }
    }
  }
}
