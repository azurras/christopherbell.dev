package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

  @Test
  void resolveClientIp_whenForwardedForFromUntrustedRemote_usesRemoteAddress() {
    var properties = new ClientIpProperties();
    var resolver = new ClientIpResolver(properties);
    var request = request("10.0.0.20", "203.0.113.10");

    assertEquals("10.0.0.20", resolver.resolveClientIp(request));
  }

  @Test
  void resolveClientIp_whenForwardedForFromTrustedRemote_usesFirstForwardedAddress() {
    var properties = new ClientIpProperties();
    properties.setTrustedProxies(java.util.List.of("10.0.0.1"));
    var resolver = new ClientIpResolver(properties);
    var request = request("10.0.0.1", "203.0.113.10, 10.0.0.1");

    assertEquals("203.0.113.10", resolver.resolveClientIp(request));
  }

  @Test
  void resolveClientIp_whenTrustedProxySendsBlankForwardedFor_usesRemoteAddress() {
    var properties = new ClientIpProperties();
    properties.setTrustedProxies(java.util.List.of("10.0.0.1"));
    var resolver = new ClientIpResolver(properties);
    var request = request("10.0.0.1", " ");

    assertEquals("10.0.0.1", resolver.resolveClientIp(request));
  }

  @Test
  void constructor_whenTrustedProxyCidrIsInvalid_failsStartup() {
    var properties = new ClientIpProperties();
    properties.setTrustedProxies(java.util.List.of("10.0.0.1/99"));

    assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(properties));
  }

  @Test
  void constructor_whenIpv4UsesAmbiguousShorthand_failsStartup() {
    var properties = new ClientIpProperties();
    properties.setTrustedProxies(java.util.List.of("127.1/32"));

    assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(properties));
  }

  @Test
  void resolveClientIp_walksTrustedChainFromNearestHopAndIgnoresSpoofedPrefix() {
    var properties = new ClientIpProperties();
    properties.setTrustedProxies(java.util.List.of("127.0.0.1/32", "10.0.0.0/8"));
    var resolver = new ClientIpResolver(properties);
    var request = request(
        "127.0.0.1",
        "198.51.100.77, 203.0.113.25, 10.20.30.40");

    assertEquals("203.0.113.25", resolver.resolveClientIp(request));
  }

  @Test
  void resolveClientIp_supportsIpv6LoopbackTunnelHop() {
    var properties = new ClientIpProperties();
    properties.setTrustedProxies(java.util.List.of("::1/128"));
    var resolver = new ClientIpResolver(properties);
    var request = request("0:0:0:0:0:0:0:1", "2001:db8::42");

    assertEquals("2001:db8::42", resolver.resolveClientIp(request));
  }

  private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    request.addHeader("X-Forwarded-For", forwardedFor);
    return request;
  }
}
