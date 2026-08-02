package dev.christopherbell.libs.pagination;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Encodes and validates opaque pagination cursors at the HTTP trust boundary. */
@Component
public final class StableCursorCodec {
  private static final String VERSION = "v1";
  private static final int MAX_ENCODED_LENGTH = 512;
  private static final String INVALID_CURSOR_MESSAGE = "Invalid pagination cursor.";

  /** Encodes a validated cursor without padding for URL query use. */
  public String encode(StableCursor cursor) {
    var value = String.join("\n", VERSION, cursor.timestamp().toString(), cursor.id());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  /** Returns empty for the first page and rejects every malformed cursor partition. */
  public Optional<StableCursor> decode(String encoded) throws InvalidRequestException {
    if (encoded == null || encoded.isBlank()) {
      return Optional.empty();
    }

    var normalized = encoded.strip();
    if (normalized.length() > MAX_ENCODED_LENGTH) {
      throw invalidCursor(null);
    }

    try {
      var decoded = new String(
          Base64.getUrlDecoder().decode(normalized), StandardCharsets.UTF_8);
      var parts = decoded.split("\n", -1);
      if (parts.length != 3 || !VERSION.equals(parts[0])) {
        throw invalidCursor(null);
      }
      return Optional.of(new StableCursor(Instant.parse(parts[1]), parts[2]));
    } catch (InvalidRequestException exception) {
      throw exception;
    } catch (IllegalArgumentException | DateTimeParseException exception) {
      throw invalidCursor(exception);
    }
  }

  private InvalidRequestException invalidCursor(Exception cause) {
    return cause == null
        ? new InvalidRequestException(INVALID_CURSOR_MESSAGE)
        : new InvalidRequestException(INVALID_CURSOR_MESSAGE, cause);
  }
}
