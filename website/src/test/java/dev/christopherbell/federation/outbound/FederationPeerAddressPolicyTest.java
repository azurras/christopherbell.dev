package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class FederationPeerAddressPolicyTest {

  @Test
  void resolvesEveryAnswerAndPinsOneDeterministicPublicAddress() throws Exception {
    var policy = new FederationPeerAddressPolicy(host -> List.of(
        InetAddress.getByName("2606:4700:4700::1111"),
        InetAddress.getByName("1.1.1.1")));

    var target = policy.validateAndResolve(
        peer("https://social.example/inbox"), URI.create("https://christopherbell.dev"), false);

    assertThat(target.inbox()).isEqualTo(URI.create("https://social.example/inbox"));
    assertThat(target.originalHost()).isEqualTo("social.example");
    assertThat(target.remoteAddress().getAddress()).isEqualTo(InetAddress.getByName("1.1.1.1"));
    assertThat(target.remoteAddress().getPort()).isEqualTo(443);
  }

  @Test
  void rejectsMalformedOrUnboundedPeerUrisBeforeDns() {
    var policy = new FederationPeerAddressPolicy(
        host -> List.of(InetAddress.getByName("1.1.1.1")));
    var publicOrigin = URI.create("https://christopherbell.dev");

    for (var uri : List.of(
        "http://social.example/inbox",
        "https://social.example:8443/inbox",
        "https://user:password@social.example/inbox",
        "https://social.example/inbox?shared=true",
        "https://social.example/inbox#fragment",
        "https://social.example")) {
      assertThatThrownBy(
          () -> policy.validateAndResolve(peer(uri), publicOrigin, false), uri)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsAnyUnsafeOrMixedDnsAnswer() throws Exception {
    var publicOrigin = URI.create("https://christopherbell.dev");
    var blocked = List.of(
        InetAddress.getByName("0.0.0.0"),
        InetAddress.getByName("10.0.0.1"),
        InetAddress.getByName("100.64.0.1"),
        InetAddress.getByName("127.0.0.1"),
        InetAddress.getByName("169.254.1.1"),
        InetAddress.getByName("172.16.0.1"),
        InetAddress.getByName("192.0.2.1"),
        InetAddress.getByName("192.168.1.1"),
        InetAddress.getByName("198.18.0.1"),
        InetAddress.getByName("198.51.100.1"),
        InetAddress.getByName("203.0.113.1"),
        InetAddress.getByName("224.0.0.1"),
        InetAddress.getByName("240.0.0.1"),
        InetAddress.getByName("::"),
        InetAddress.getByName("::1"),
        InetAddress.getByName("fc00::1"),
        InetAddress.getByName("fe80::1"),
        InetAddress.getByName("ff00::1"),
        InetAddress.getByName("2001:db8::1"),
        mappedIpv6("10.0.0.1"));

    for (var address : blocked) {
      var policy = new FederationPeerAddressPolicy(host -> List.of(address));
      assertThatThrownBy(() -> policy.validateAndResolve(
          peer("https://blocked.example/inbox"), publicOrigin, false), address.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }

    var mixed = new FederationPeerAddressPolicy(host -> List.of(
        InetAddress.getByName("1.1.1.1"), InetAddress.getByName("10.0.0.1")));
    assertThatThrownBy(() -> mixed.validateAndResolve(
        peer("https://mixed.example/inbox"), publicOrigin, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void permitsHttpLoopbackOnlyForExplicitLoopbackDevelopment() throws Exception {
    var policy = new FederationPeerAddressPolicy(
        host -> List.of(InetAddress.getByName("127.0.0.1")));
    var peer = peer("http://127.0.0.1:9081/inbox");

    assertThatCode(() -> policy.validateAndResolve(
        peer, URI.create("http://127.0.0.1:8081"), true))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.validateAndResolve(
        peer, URI.create("https://christopherbell.dev"), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.validateAndResolve(
        peer, URI.create("http://127.0.0.1:8081"), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyAndUnresolvableDnsAnswers() {
    var publicOrigin = URI.create("https://christopherbell.dev");

    assertThatThrownBy(() -> new FederationPeerAddressPolicy(host -> List.of())
        .validateAndResolve(peer("https://empty.example/inbox"), publicOrigin, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FederationPeerAddressPolicy(host -> {
      throw new java.net.UnknownHostException(host);
    }).validateAndResolve(peer("https://missing.example/inbox"), publicOrigin, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(java.net.UnknownHostException.class);
  }

  private static ControlledPeer peer(String inbox) {
    return new ControlledPeer("test-peer", URI.create(inbox));
  }

  private static InetAddress mappedIpv6(String ipv4) throws Exception {
    var ipv4Bytes = InetAddress.getByName(ipv4).getAddress();
    var bytes = new byte[16];
    bytes[10] = (byte) 0xff;
    bytes[11] = (byte) 0xff;
    System.arraycopy(ipv4Bytes, 0, bytes, 12, ipv4Bytes.length);
    return Inet6Address.getByAddress(null, bytes, -1);
  }
}
