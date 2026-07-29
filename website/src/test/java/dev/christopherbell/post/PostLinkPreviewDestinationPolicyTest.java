package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.post.preview.PostLinkPreviewDestinationPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PostLinkPreviewDestinationPolicyTest {
  @Test
  void rejectsUnsafeSchemesUserinfoAndNonPublicDnsAnswers() throws Exception {
    var policy = new PostLinkPreviewDestinationPolicy(host -> switch (host) {
      case "public.example" -> List.of(InetAddress.getByName("93.184.216.34"));
      case "mixed.example" -> List.of(
          InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1"));
      case "docs.example" -> List.of(InetAddress.getByName("192.0.2.10"));
      case "ipv6-docs.example" -> List.of(InetAddress.getByName("2001:db8::1"));
      default -> List.of(InetAddress.getByName("127.0.0.1"));
    });

    assertThatCode(() -> policy.requirePublic(URI.create("https://public.example/page")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.requirePublic(URI.create("file:///etc/passwd")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.requirePublic(URI.create("https://user:pass@public.example/")))
        .isInstanceOf(IllegalArgumentException.class);
    for (var host : List.of(
        "localhost", "mixed.example", "docs.example", "ipv6-docs.example")) {
      assertThatThrownBy(() -> policy.requirePublic(URI.create("https://" + host + "/")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsPrivateLinkLocalMulticastAndReservedIpv4AndIpv6Ranges() throws Exception {
    var blocked = List.of(
        "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1",
        "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
        "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
        "::", "::1", "fc00::1", "fe80::1", "ff00::1",
        "2001::1", "2001:1::4", "2001:2::1", "2001:10::1", "2001:40::1",
        "2001:db8::1", "2002::1", "3fff::1");
    for (var address : blocked) {
      var policy = new PostLinkPreviewDestinationPolicy(
          host -> List.of(InetAddress.getByName(address)));
      assertThatThrownBy(() -> policy.requirePublic(URI.create(
          "https://blocked.example/" + address.replace(':', '-'))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void permitsOnlyGloballyReachableExceptionsWithinIetfProtocolAssignments() throws Exception {
    for (var address : List.of(
        "2001:1::1", "2001:1::2", "2001:1::3", "2001:3::1",
        "2001:4:112::1", "2001:20::1", "2001:30::1", "2606:4700:4700::1111")) {
      var policy = new PostLinkPreviewDestinationPolicy(
          host -> List.of(InetAddress.getByName(address)));

      assertThatCode(() -> policy.requirePublic(URI.create("https://public.example/")))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void rejectsHostIdentitiesThatCannotBeSafelyAppliedToTlsBeforeResolution() {
    var resolutions = new AtomicInteger();
    var policy = new PostLinkPreviewDestinationPolicy(host -> {
      resolutions.incrementAndGet();
      return List.of(InetAddress.getByName("1.1.1.1"));
    });

    for (var uri : List.of(
        URI.create("https://public.example./"),
        URI.create("https://[2606:4700:4700::1111]/"))) {
      assertThatThrownBy(() -> policy.requirePublic(uri))
          .isInstanceOf(IllegalArgumentException.class);
    }
    org.assertj.core.api.Assertions.assertThat(resolutions).hasValue(0);
  }

  @Test
  void returnsAnAddressPinnedToTheExactValidatedDnsAnswerSet() throws Exception {
    var first = InetAddress.getByName("1.1.1.1");
    var second = InetAddress.getByName("8.8.8.8");
    var policy = new PostLinkPreviewDestinationPolicy(host -> List.of(second, first));
    var uri = URI.create("https://public.example/path?q=1");

    var approved = policy.resolveApproved(uri, Duration.ofSeconds(1));

    assertThatCode(() -> approved.remoteAddress().getAddress()).doesNotThrowAnyException();
    org.assertj.core.api.Assertions.assertThat(approved.uri()).isEqualTo(uri);
    org.assertj.core.api.Assertions.assertThat(approved.originalHost()).isEqualTo("public.example");
    org.assertj.core.api.Assertions.assertThat(approved.approvedAddresses())
        .containsExactlyInAnyOrder(first, second)
        .contains(approved.remoteAddress().getAddress());
    org.assertj.core.api.Assertions.assertThat(approved.remoteAddress().getPort()).isEqualTo(443);
  }

  @Test
  void boundsDnsResolutionWithThePerHopDeadline() {
    var policy = new PostLinkPreviewDestinationPolicy(host -> {
      try {
        Thread.sleep(5_000);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      return List.of(InetAddress.getLoopbackAddress());
    });
    var started = System.nanoTime();

    assertThatThrownBy(() -> policy.resolveApproved(
        URI.create("https://slow-dns.example/"), Duration.ofMillis(50)))
        .isInstanceOf(dev.christopherbell.post.preview.LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("TIMEOUT");
    org.assertj.core.api.Assertions.assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void approvedDestinationRejectsHostOrPortThatDoesNotMatchItsUri() {
    var uri = URI.create("https://public.example/path");
    var address = InetAddress.getLoopbackAddress();

    assertThatThrownBy(() -> new PostLinkPreviewDestinationPolicy.ApprovedDestination(
        uri, "different.example", List.of(address), new InetSocketAddress(address, 443)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostLinkPreviewDestinationPolicy.ApprovedDestination(
        uri, "public.example", List.of(address), new InetSocketAddress(address, 8443)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
