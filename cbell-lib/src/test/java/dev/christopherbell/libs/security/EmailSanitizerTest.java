package dev.christopherbell.libs.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmailSanitizer (pragmatic): normalization, strict whitespace, ASCII local, IDN domain")
public final class EmailSanitizerTest {

  // ---------- valid paths ----------

  @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
  @DisplayName("Sanitize returns normalized value when input is valid")
  @CsvSource({
      // basic lowercase
      "USER@EXAMPLE.COM,user@example.com",
      // display-name extraction
      "'  Name  <User@Example.com>  ',user@example.com",
      // mailto wrapper
      "mailto:User@Example.com,user@example.com",
      // trailing dot trimmed
      "a.b@example.com.,a.b@example.com",
      // allowed ascii local punctuation
      "john.doe+tag_1%test@example.com,john.doe+tag_1%test@example.com",
      // localhost
      "root@localhost,root@localhost",
      // IPv4 literal (strict numeric)
      "a@127.0.0.1,a@127.0.0.1",
      // IPv6 literal (bracketed)
      "user@[::1],user@[::1]",
      // IPv6 literal with IPv6: prefix
      "user@[IPv6:2001:db8::1],user@[2001:db8::1]",
      // IDN domain — punycoded using STD3 rules
      "jose@example.com,jose@example.com",
      "jose@ex\u00E1mple.com,jose@xn--exmple-qta.com" // exámple.com -> xn--exmple-qta.com
  })
  public void testSanitize_whenValidCases(String in, String expected) {
    assertEquals(expected, EmailSanitizer.sanitize(in));
  }

  // ---------- invalid: structure / wrappers / whitespace ----------

  @Test @DisplayName("Throws when input is null")
  public void testSanitize_whenInputIsNull() {
    assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize(null));
  }

  @Test @DisplayName("Throws when input is empty/blank")
  public void testSanitize_whenInputIsEmptyOrBlank() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("   "))
    );
  }

  @Test
  @DisplayName("Leading/trailing ASCII spaces are trimmed before parse")
  public void testSanitize_whenInputHasLeadingOrTrailingSpaces() {
    assertEquals("user@example.com", EmailSanitizer.sanitize("  user@example.com  "));
  }

  @Test
  @DisplayName("Control characters (e.g., TAB) are stripped, then parsed")
  public void testSanitize_whenControlsAreStrippedThenParsed() {
    // \t is removed by CONTROL/FORMAT filter → valid address remains
    assertEquals("user@example.com", EmailSanitizer.sanitize("user@\texample.com"));
  }

  @Test @DisplayName("Throws when '@' count is not exactly one")
  public void testSanitize_whenAtCountInvalid() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("no-at-here")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("a@@b.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("@example.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@"))
    );
  }

  // ---------- invalid: local-part rules ----------

  @ParameterizedTest
  @DisplayName("Throws when local contains disallowed characters")
  @CsvSource({
      "'user!@example.com'",
      "'us$er@example.com'",
      "'us-er()@example.com'",
      "'us\uD83D\uDE00er@example.com'", // emoji in local
      "'jos\u00E9@example.com'"       // non-ASCII letter in local
  })
  public void testSanitize_whenLocalHasInvalidCharacters(String input) {
    assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize(input));
  }

  @Test @DisplayName("Throws when local has bad dots or length > 64")
  public void testSanitize_whenLocalHasDotIssuesOrTooLong() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize(".user@example.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user.@example.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("a..b@example.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("a".repeat(65) + "@example.com"))
    );
  }

  // ---------- invalid: domain rules ----------

  @Test @DisplayName("Throws when domain label char invalid or hyphen at edges")
  public void testSanitize_whenDomainLabelInvalid() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@exa_mple.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@exam!ple.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@-bad.com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@bad-.com"))
    );
  }

  @Test @DisplayName("Throws when domain has no dot (except special cases)")
  public void testSanitize_whenDomainLacksDot() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@example")) // tld missing
    );
  }

  @Test @DisplayName("Throws when domain label length > 63 or domain length > 253")
  public void testSanitize_whenDomainLengthsExceeded() {
    String longLabel = "a".repeat(64);
    String domainTooLong = String.join(".", "a".repeat(63), "a".repeat(63), "a".repeat(63), "a".repeat(63), "a".repeat(63));
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@" + longLabel + ".com")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@" + domainTooLong))
    );
  }

  @Test
  @DisplayName("Throws when TLD does not start with a letter")
  public void testSanitize_whenTldDoesNotStartWithLetter() {
    assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@example.123"));
  }

  @Test
  @DisplayName("Throws when total length exceeds 254")
  public void testSanitize_whenTotalLengthTooLong() {
    String local = "a".repeat(64);
    // domain length 189 so 64 + 1 + 189 = 254 is OK; make it 190 to exceed
    String label63 = "a".repeat(63);
    String domain = String.join(".", label63, label63, "a".repeat(64 - 1)); // 63+1+63+1+63 = 191 incl dots? Safer: build explicitly
    // Build a domain of length 190
    StringBuilder d = new StringBuilder();
    d.append("a".repeat(63)).append('.').append("a".repeat(63)).append('.').append("a".repeat(62)); // 63+1+63+1+62 = 190
    String tooLong = local + "@" + d;
    assertTrue(tooLong.length() > 254);
    assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize(local + "@" + d));
  }

  @Test
  @DisplayName("Throws when IPv6 literal is invalid")
  public void testSanitize_whenIpv6LiteralInvalid() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@[::1:zz]")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@[IPv6:gggg::1]")),
        () -> assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@[notipv6]"))
    );
  }

  @Test
  @DisplayName("Preserves the IPv6 parser cause behind the safe validation message")
  public void testSanitize_whenIpv6ParserRejectsLiteral() {
    IllegalArgumentException failure = assertThrows(
        IllegalArgumentException.class,
        () -> EmailSanitizer.sanitize("user@[::1:zz]")
    );

    assertEquals("invalid IPv6 literal", failure.getMessage());
    assertInstanceOf(UnknownHostException.class, failure.getCause());
  }

  @Test
  @DisplayName("Throws when IDN domain is invalid (emoji label rejected by STD3)")
  public void testSanitize_whenIdnEmojiLabelInvalid() {
    assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("user@😀.com"));
  }

  @Test
  @DisplayName("Preserves the IDN parser cause behind the safe validation message")
  public void testSanitize_whenIdnParserRejectsDomain() {
    IllegalArgumentException failure = assertThrows(
        IllegalArgumentException.class,
        () -> EmailSanitizer.sanitize("user@😀.com")
    );

    assertEquals("invalid idn domain", failure.getMessage());
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  @Test
  @DisplayName("Accepts Unicode domain by punycoding; rejects Unicode local")
  public void testSanitize_whenUnicodeDomainButAsciiLocalOnly() {
    assertEquals("user@xn--exmple-qta.com", EmailSanitizer.sanitize("user@ex\u00E1mple.com"));
    assertThrows(IllegalArgumentException.class, () -> EmailSanitizer.sanitize("jos\u00E9@example.com"));
  }
}
