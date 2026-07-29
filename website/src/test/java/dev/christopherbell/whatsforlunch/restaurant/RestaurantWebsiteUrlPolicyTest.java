package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RestaurantWebsiteUrlPolicyTest {
  @ParameterizedTest
  @CsvSource({
      "' HTTPS://Example.com/menu ', 'https://Example.com/menu'",
      "'http://example.com', 'http://example.com'"
  })
  void acceptsOnlyTrimmedAbsoluteHttpUrls(String input, String expected) throws Exception {
    assertThat(RestaurantWebsiteUrlPolicy.requireSafe(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "javascript:alert(1)",
      "data:text/html,hello",
      "//example.com/path",
      "ftp://example.com/file",
      "https://user:password@example.com/path",
      "https:///missing-host"
  })
  void rejectsUnsafeOrAmbiguousSchemes(String input) {
    assertThatThrownBy(() -> RestaurantWebsiteUrlPolicy.requireSafe(input))
        .isInstanceOf(InvalidRequestException.class);
    assertThat(RestaurantWebsiteUrlPolicy.safeOrNull(input)).isNull();
  }
}
