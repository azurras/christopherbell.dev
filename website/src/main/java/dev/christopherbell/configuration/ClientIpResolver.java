package dev.christopherbell.configuration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Resolves the effective client IP while only trusting forwarding headers from known proxies.
 */
@RequiredArgsConstructor
public class ClientIpResolver {
  private final ClientIpProperties properties;

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

    var firstForwarded = forwardedFor.split(",")[0].trim();
    return firstForwarded.isBlank() ? remoteAddress : firstForwarded;
  }

  private boolean isTrustedProxy(String remoteAddress) {
    return properties.getTrustedProxies().stream()
        .anyMatch(proxy -> matchesProxy(remoteAddress, proxy));
  }

  private boolean matchesProxy(String remoteAddress, String proxy) {
    if (proxy == null || proxy.isBlank()) {
      return false;
    }
    var normalizedProxy = proxy.trim();
    if (!normalizedProxy.contains("/")) {
      return normalizedProxy.equals(remoteAddress);
    }
    return matchesIpv4Cidr(remoteAddress, normalizedProxy);
  }

  private boolean matchesIpv4Cidr(String remoteAddress, String cidr) {
    var parts = cidr.split("/", 2);
    if (parts.length != 2) {
      return false;
    }
    try {
      var prefixLength = Integer.parseInt(parts[1]);
      if (prefixLength < 0 || prefixLength > 32) {
        return false;
      }
      var mask = prefixLength == 0 ? 0 : -1L << (32 - prefixLength);
      return (ipv4ToLong(remoteAddress) & mask) == (ipv4ToLong(parts[0]) & mask);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private long ipv4ToLong(String address) {
    List<String> octets = List.of(address.split("\\."));
    if (octets.size() != 4) {
      throw new IllegalArgumentException("Only IPv4 CIDR ranges are supported.");
    }
    long result = 0;
    for (String octet : octets) {
      var value = Integer.parseInt(octet);
      if (value < 0 || value > 255) {
        throw new IllegalArgumentException("IPv4 octet out of range.");
      }
      result = (result << 8) + value;
    }
    return result;
  }
}
