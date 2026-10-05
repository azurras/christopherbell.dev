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

    String s = input.strip();
    if (s.isEmpty()) throw new IllegalArgumentException("email is empty");

    // Wrappers
    if (s.regionMatches(true, 0, "mailto:", 0, 7)) {
      s = s.substring(7).strip();
    }
    int lt = s.indexOf('<');
    int gt = s.lastIndexOf('>');
    if (lt >= 0 && gt > lt) {
      s = s.substring(lt + 1, gt).strip();
    }

    // Normalize
    s = CONTROL_OR_FORMAT.matcher(s).replaceAll("");
    s = Normalizer.normalize(s, Form.NFKC);

    // No whitespace allowed going forward (no collapsing)
    if (WHITESPACE.matcher(s).find()) {
      throw new IllegalArgumentException("whitespace not allowed");
    }

    // Split
    int at = s.lastIndexOf('@');
    if (at <= 0 || at == s.length() - 1 || s.indexOf('@') != at) {
      throw new IllegalArgumentException("email must contain exactly one '@'");
    }
    String local = s.substring(0, at).toLowerCase(Locale.ROOT);
    String domain = s.substring(at + 1).toLowerCase(Locale.ROOT);

    // Trim a trailing '.' (common typo)
    if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);

    // Validate local
    validateLocal(local);

    // Validate/normalize domain
    domain = normalizeDomain(domain);

    // Total length (local + '@' + domain)
    int total = local.length() + 1 + domain.length();
    if (total > MAX_EMAIL_LEN) throw new IllegalArgumentException("email too long");

    return local + "@" + domain;
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

  private static String normalizeDomain(String domain) {
    // localhost
    if ("localhost".equals(domain)) return domain;

    // IPv4 unbracketed
    if (IPV4.matcher(domain).matches()) return domain;

    // Bracketed IPv6 literal: [::1] or [IPv6::1]
    if (domain.startsWith("[") && domain.endsWith("]")) {
      String inside = domain.substring(1, domain.length() - 1);
      // Allow optional "IPv6:" prefix (case-insensitive)
      if (inside.regionMatches(true, 0, "IPv6:", 0, 5)) inside = inside.substring(5);
      try {
        InetAddress addr = InetAddress.getByName(inside);
        if (!(addr instanceof Inet6Address)) throw new IllegalArgumentException("invalid IPv6 literal");
        return "[" + inside + "]";
      } catch (UnknownHostException | IllegalArgumentException e) {
        throw new IllegalArgumentException("invalid IPv6 literal", e);
      }
    }

    // IDN punycode (STD3 rules)
    String ascii;
    try {
      ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid idn domain", e);
    }
    if (ascii.isEmpty() || ascii.length() > MAX_DOMAIN_LEN) {
      throw new IllegalArgumentException("bad domain length");
    }

    String[] labels = ascii.split("\\.");
    if (labels.length < 2) throw new IllegalArgumentException("domain must contain a dot");
    for (String label : labels) {
      if (label.isEmpty() || label.length() > 63) throw new IllegalArgumentException("bad domain label length");
      if (!LABEL_SAFE.matcher(label).matches()) throw new IllegalArgumentException("invalid domain label char");
      if (label.startsWith("-") || label.endsWith("-")) throw new IllegalArgumentException("hyphen placement");
    }
    String tld = labels[labels.length - 1];
    char c0 = tld.charAt(0);
    if (!(c0 >= 'a' && c0 <= 'z')) {
      throw new IllegalArgumentException("tld must start with a letter");
    }
    return ascii;
  }
}
