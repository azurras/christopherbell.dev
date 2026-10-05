package dev.christopherbell.libs.security;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility class for sanitizing and validating email addresses.
 *
 * This class provides a method to normalize and validate email addresses
 * according to a pragmatic, deliverability-first profile. It handles common
 * wrappers, Unicode normalization, and enforces rules on the local part and
 * domain part of the email address.
 */
public final class EmailSanitizer {
  private EmailSanitizer() {}

  // Unicode categories:
  // - Cntrl: control chars
  // - Cf: format chars (incl zero-width)
  private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");
  private static final Pattern WHITESPACE = Pattern.compile("\\s");
  private static final Pattern LOCAL_SAFE = Pattern.compile("[a-z0-9._%+\\-]+");
  private static final Pattern IPV4 =
      Pattern.compile("(?i)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
  private static final Pattern LABEL_SAFE = Pattern.compile("[a-z0-9-]+"); // post-IDN ASCII
  private static final int MAX_LOCAL_LEN = 64;
  private static final int MAX_DOMAIN_LEN = 253; // DNS limit
  private static final int MAX_EMAIL_LEN = 254;  // common practical limit

  /**
   * Normalize and validate an email for storage/compare.
   * Pragmatic (deliverability-first) profile:
   * - Accepts "mailto:" and "Name <addr>" wrappers.
   * - NFKC normalize, strip control/format chars.
   * - Rejects any remaining whitespace.
   * - Local-part limited to ASCII [a-z0-9._%+-], dot rules enforced, length ≤ 64.
   * - Domain: allow localhost, strict IPv4, strict bracketed IPv6; else IDN punycode (STD3)
   *   then ASCII label checks; at least one dot; final label starts with a letter.
   * - Lowercase both parts; trim a single trailing '.' on domain (common typo).
   * - Enforces total length ≤ 254.
   */
  public static String sanitize(String input) {
    if (input == null) throw new IllegalArgumentException("email is null");

    String strippedInput = input.strip();
    if (strippedInput.isEmpty()) throw new IllegalArgumentException("email is empty");

    // Wrappers
    String emailWithoutMailtoPrefix = strippedInput;
    if (emailWithoutMailtoPrefix.regionMatches(true, 0, "mailto:", 0, 7)) {
      emailWithoutMailtoPrefix = emailWithoutMailtoPrefix.substring(7).strip();
    }
    int openingAngleBracketIndex = emailWithoutMailtoPrefix.indexOf('<');
    int closingAngleBracketIndex = emailWithoutMailtoPrefix.lastIndexOf('>');
    String emailWithoutDisplayName = emailWithoutMailtoPrefix;
    if (openingAngleBracketIndex >= 0 && closingAngleBracketIndex > openingAngleBracketIndex) {
      emailWithoutDisplayName = emailWithoutMailtoPrefix
          .substring(openingAngleBracketIndex + 1, closingAngleBracketIndex)
          .strip();
    }

    // Normalize
    String emailWithoutControlOrFormatCharacters =
        CONTROL_OR_FORMAT.matcher(emailWithoutDisplayName).replaceAll("");
    String normalizedEmail = Normalizer.normalize(emailWithoutControlOrFormatCharacters, Form.NFKC);

    // No whitespace allowed going forward (no collapsing)
    if (WHITESPACE.matcher(normalizedEmail).find()) {
      throw new IllegalArgumentException("whitespace not allowed");
    }

    // Split
    int atSignIndex = normalizedEmail.lastIndexOf('@');
    if (atSignIndex <= 0 || atSignIndex == normalizedEmail.length() - 1
        || normalizedEmail.indexOf('@') != atSignIndex) {
      throw new IllegalArgumentException("email must contain exactly one '@'");
    }
    String lowercaseLocalPart = normalizedEmail.substring(0, atSignIndex).toLowerCase(Locale.ROOT);
    String lowercaseDomain = normalizedEmail.substring(atSignIndex + 1).toLowerCase(Locale.ROOT);

    // Trim a trailing '.' (common typo)
    String domainWithoutTrailingDot = lowercaseDomain;
    if (domainWithoutTrailingDot.endsWith(".")) {
      domainWithoutTrailingDot = domainWithoutTrailingDot.substring(0, domainWithoutTrailingDot.length() - 1);
    }

    // Validate local
    validateLocal(lowercaseLocalPart);

    // Validate/normalize domain
    String normalizedDomain = normalizeDomain(domainWithoutTrailingDot);

    // Total length (local + '@' + domain)
    int normalizedEmailLength = lowercaseLocalPart.length() + 1 + normalizedDomain.length();
    if (normalizedEmailLength > MAX_EMAIL_LEN) throw new IllegalArgumentException("email too long");

    return lowercaseLocalPart + "@" + normalizedDomain;
  }

  private static void validateLocal(String local) {
    if (local.isEmpty() || local.length() > MAX_LOCAL_LEN) {
      throw new IllegalArgumentException("bad local part length");
    }
    if (!LOCAL_SAFE.matcher(local).matches()) {
      throw new IllegalArgumentException("invalid local characters");
    }
    if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
      throw new IllegalArgumentException("invalid dot placement");
    }
  }

  private static String normalizeDomain(String domainName) {
    // localhost
    if ("localhost".equals(domainName)) return domainName;

    // IPv4 unbracketed
    if (IPV4.matcher(domainName).matches()) return domainName;

    // Bracketed IPv6 literal: [::1] or [IPv6::1]
    if (domainName.startsWith("[") && domainName.endsWith("]")) {
      String bracketedIpv6Literal = domainName.substring(1, domainName.length() - 1);
      // Allow optional "IPv6:" prefix (case-insensitive)
      String ipv6Literal = bracketedIpv6Literal;
      if (ipv6Literal.regionMatches(true, 0, "IPv6:", 0, 5)) {
        ipv6Literal = ipv6Literal.substring(5);
      }
      try {
        InetAddress ipv6Address = InetAddress.getByName(ipv6Literal);
        if (!(ipv6Address instanceof Inet6Address)) {
          throw new IllegalArgumentException("invalid IPv6 literal");
        }
        return "[" + ipv6Literal + "]";
      } catch (UnknownHostException invalidIpv6Literal) {
        throw new IllegalArgumentException("invalid IPv6 literal", invalidIpv6Literal);
      }
    }

    // IDN punycode (STD3 rules)
    String asciiDomain;
    try {
      asciiDomain = IDN.toASCII(domainName, IDN.USE_STD3_ASCII_RULES);
    } catch (IllegalArgumentException invalidIdnDomain) {
      throw new IllegalArgumentException("invalid idn domain", invalidIdnDomain);
    }
    if (asciiDomain.isEmpty() || asciiDomain.length() > MAX_DOMAIN_LEN) {
      throw new IllegalArgumentException("bad domain length");
    }

    String[] asciiDomainLabels = asciiDomain.split("\\.");
    if (asciiDomainLabels.length < 2) throw new IllegalArgumentException("domain must contain a dot");
    for (String label : asciiDomainLabels) {
      if (label.isEmpty() || label.length() > 63) throw new IllegalArgumentException("bad domain label length");
      if (!LABEL_SAFE.matcher(label).matches()) throw new IllegalArgumentException("invalid domain label char");
      if (label.startsWith("-") || label.endsWith("-")) throw new IllegalArgumentException("hyphen placement");
    }
    String topLevelDomain = asciiDomainLabels[asciiDomainLabels.length - 1];
    char topLevelDomainFirstCharacter = topLevelDomain.charAt(0);
    if (!(topLevelDomainFirstCharacter >= 'a' && topLevelDomainFirstCharacter <= 'z')) {
      throw new IllegalArgumentException("tld must start with a letter");
    }
    return asciiDomain;
  }
}
