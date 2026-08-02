package dev.christopherbell.libs.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class StableCursorCodecTest {
  private final StableCursorCodec codec = new StableCursorCodec();

  @Test
  void roundTripsTimestampAndIdentifierExactly() throws InvalidRequestException {
    var cursor = new StableCursor(Instant.parse("2026-07-26T05:00:00.123456789Z"), "item-42");

    assertThat(codec.decode(codec.encode(cursor))).contains(cursor);
  }

  @Test
  void missingCursorRepresentsTheFirstPage() throws InvalidRequestException {
    assertThat(codec.decode(null)).isEmpty();
    assertThat(codec.decode("   ")).isEmpty();
  }

  @Test
  void rejectsMalformedWrongVersionAndIncompleteCursors() {
    assertInvalid("not-base64!");
    assertInvalid(encoded("v2\n2026-07-26T05:00:00Z\nitem-42"));
    assertInvalid(encoded("v1\n2026-07-26T05:00:00Z"));
  }

  @Test
  void rejectsInvalidTimestampAndIdentifierPartitions() {
    assertInvalid(encoded("v1\nnot-an-instant\nitem-42"));
    assertInvalid(encoded("v1\n2026-07-26T05:00:00Z\n "));
    assertInvalid(encoded("v1\n2026-07-26T05:00:00Z\n" + "x".repeat(129)));
  }

  @Test
  void rejectsEncodedCursorOverTheBoundaryBeforeDecoding() {
    assertInvalid("a".repeat(513));
  }

  private void assertInvalid(String encoded) {
    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Invalid pagination cursor.");
  }

  private String encoded(String value) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
