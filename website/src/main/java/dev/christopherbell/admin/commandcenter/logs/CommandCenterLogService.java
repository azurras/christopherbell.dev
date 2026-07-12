package dev.christopherbell.admin.commandcenter.logs;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.io.IOException;
import java.io.RandomAccessFile;
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
            && cursor.fingerprint().equals(fingerprint(logPath, attributes, cursor.offset()));

    long start;
    if (cursorMatches) {
      start = cursor.offset();
    } else {
      reset = reset || cursor != null;
      start = Math.max(0, fileSize - maxBytes());
    }

    ReadWindow window = readWindow(logPath, start, fileSize, cursorMatches);
    List<LogRecord> records = filterAndBound(window.lines(), requestedLevel, query);
    String nextCursor =
        encodeCursor(
            new Cursor(
                fingerprint(logPath, attributes, window.nextOffset()), window.nextOffset()));
    return new LogPage(nextCursor, records, reset, "ok");
  }

  private ReadWindow readWindow(Path logPath, long start, long fileSize, boolean incremental)
      throws IOException {
    int length = (int) Math.min(maxBytes(), fileSize - start);
    byte[] bytes = new byte[length];
    try (var file = new RandomAccessFile(logPath.toFile(), "r")) {
      file.seek(start);
      file.readFully(bytes);
    }

    int from = 0;
    if (!incremental && start > 0) {
      from = afterFirstNewline(bytes);
    }

    List<RawLine> lines = splitLines(bytes, from, start);
    return new ReadWindow(lines, start + bytes.length);
  }

  private List<LogRecord> filterAndBound(
      List<RawLine> lines, LogLevel requestedLevel, String query) {
    List<LogRecord> matches = new ArrayList<>();
    for (RawLine line : lines) {
      String redacted = redact(new String(line.bytes(), UTF_8));
      LogLevel actualLevel = classify(redacted);
      if ((requestedLevel == null || requestedLevel == actualLevel)
          && (query == null || redacted.contains(query))) {
        matches.add(new LogRecord(line.offset(), actualLevel == null ? "" : actualLevel.name(), redacted));
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

  private List<RawLine> splitLines(byte[] bytes, int from, long absoluteStart) {
    List<RawLine> lines = new ArrayList<>();
    int lineStart = from;
    for (int index = from; index <= bytes.length; index++) {
      boolean atEnd = index == bytes.length;
      if (!atEnd && bytes[index] != '\n') {
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
    return lines;
  }

  private int afterFirstNewline(byte[] bytes) {
    for (int index = 0; index < bytes.length; index++) {
      if (bytes[index] == '\n') {
        return index + 1;
      }
    }
    return bytes.length;
  }

  private String redact(String text) {
    String redacted = AUTHORIZATION_PATTERN.matcher(text).replaceAll("$1[REDACTED]");
    redacted = NAMED_SECRET_PATTERN.matcher(redacted).replaceAll("$1$2[REDACTED]");
    return JWT_PATTERN.matcher(redacted).replaceAll("[REDACTED]");
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
    return query;
  }

  private CursorResult decodeCursor(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return new CursorResult(null, false, false);
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(encoded), UTF_8);
      String[] parts = decoded.split(":", -1);
      if (parts.length != 3 || !parts[0].equals("v1")) {
        throw new IllegalArgumentException("Unsupported cursor");
      }
      long offset = Long.parseLong(parts[2]);
      if (parts[1].isBlank() || offset < 0) {
        throw new IllegalArgumentException("Invalid cursor");
      }
      return new CursorResult(new Cursor(parts[1], offset), true, false);
    } catch (IllegalArgumentException exception) {
      return new CursorResult(null, true, true);
    }
  }

  private String encodeCursor(Cursor cursor) {
    String value = "v1:" + cursor.fingerprint() + ":" + cursor.offset();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(UTF_8));
  }

  private String fingerprint(Path path, BasicFileAttributes attributes, long offset)
      throws IOException {
    Object fileKey = attributes.fileKey();
    String identity =
        path.toAbsolutePath().normalize()
            + "|"
            + (fileKey == null ? "" : fileKey)
            + "|"
            + attributes.creationTime().toMillis()
            + "|"
            + Base64.getEncoder().encodeToString(readAnchor(path, offset));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for log cursors", exception);
    }
  }

  private byte[] readAnchor(Path path, long offset) throws IOException {
    int length = (int) Math.min(128, offset);
    byte[] anchor = new byte[length];
    try (var file = new RandomAccessFile(path.toFile(), "r")) {
      file.seek(offset - length);
      file.readFully(anchor);
    }
    return anchor;
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

  private record Cursor(String fingerprint, long offset) {}

  private record CursorResult(Cursor cursor, boolean provided, boolean invalid) {}

  private record RawLine(long offset, byte[] bytes) {}

  private record ReadWindow(List<RawLine> lines, long nextOffset) {}

  private enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    private static LogLevel parse(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(
            "Log level must be TRACE, DEBUG, INFO, WARN, or ERROR", exception);
      }
    }
  }
}
