package dev.christopherbell.configuration;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Resolves the effective client IP while only trusting forwarding headers from known proxies.
 */
public class ClientIpResolver {
  private static final int MAX_FORWARDED_HOPS = 32;
  private final List<IpAddressMatcher> trustedProxies;

  public ClientIpResolver(ClientIpProperties properties) {
    this.trustedProxies = properties.getTrustedProxies().stream()
        .map(ClientIpResolver::validatedMatcher)
        .toList();
  }

  /**
   * Returns the first forwarded IP only when the immediate remote address is trusted.
   *
   * @param request current HTTP request
   * @return effective client IP address
   */
  public String resolveClientIp(HttpServletRequest request) {
    var remoteAddress = request.getRemoteAddr();
    var forwardedFor = request.getHeader("X-Forwarded-For");
    if (!isTrustedProxy(remoteAddress) || forwardedFor == null || forwardedFor.isBlank()) {
      return remoteAddress;
    }
    var hops = Arrays.stream(forwardedFor.split(",", -1))
        .map(String::trim)
        .toList();
    if (hops.isEmpty() || hops.size() > MAX_FORWARDED_HOPS
        || hops.stream().anyMatch(hop -> hop.isEmpty() || !isIpLiteral(hop))) {
      return remoteAddress;
    }
    for (int index = hops.size() - 1; index >= 0; index--) {
      var hop = hops.get(index);
      if (!isTrustedProxy(hop)) return hop;
    }
    return hops.getFirst();
  }

  private boolean isTrustedProxy(String remoteAddress) {
    return trustedProxies.stream().anyMatch(proxy -> proxy.matches(remoteAddress));
  }

  private static IpAddressMatcher validatedMatcher(String configuredRange) {
    if (configuredRange == null || configuredRange.isBlank()) {
      throw new IllegalArgumentException("Trusted proxy entries must not be blank.");
    }
    var normalized = configuredRange.trim();
    var parts = normalized.split("/", -1);
    if (parts.length > 2 || !isIpLiteral(parts[0])) {
      throw new IllegalArgumentException("Trusted proxy must be an IP literal or CIDR: "
          + normalized);
    }
    if (parts.length == 2) {
      var address = parseLiteral(parts[0]);
      try {
        var prefix = Integer.parseInt(parts[1]);
        var maximum = address instanceof Inet4Address ? 32 : 128;
        if (prefix < 0 || prefix > maximum) {
          throw new IllegalArgumentException("Trusted proxy CIDR prefix is out of range: "
              + normalized);
        }
      } catch (NumberFormatException malformedPrefix) {
        throw new IllegalArgumentException("Trusted proxy CIDR prefix is invalid: "
            + normalized, malformedPrefix);
      }
    }
    return new IpAddressMatcher(normalized);
  }

  private static boolean isIpLiteral(String value) {
    if (value == null || value.isBlank()) return false;
    if (!value.matches("[0-9A-Fa-f:.]+")) return false;
    if (!value.contains(":")) {
      var octets = value.split("\\.", -1);
      if (octets.length != 4) return false;
      for (var octet : octets) {
        if (!octet.matches("0|[1-9][0-9]{0,2}")) return false;
        if (Integer.parseInt(octet) > 255) return false;
      }
      return true;
    }
    try {
      var parsed = parseLiteral(value);
      return parsed instanceof Inet6Address;
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static InetAddress parseLiteral(String value) {
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException malformed) {
      throw new IllegalArgumentException("Invalid IP literal: " + value, malformed);
    }
  }
}
