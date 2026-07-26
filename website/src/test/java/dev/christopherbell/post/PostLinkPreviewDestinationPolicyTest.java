package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.post.preview.PostLinkPreviewDestinationPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
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
        "::", "::1", "fc00::1", "fe80::1", "ff00::1", "2001:db8::1");
    for (var address : blocked) {
      var policy = new PostLinkPreviewDestinationPolicy(
          host -> List.of(InetAddress.getByName(address)));
      assertThatThrownBy(() -> policy.requirePublic(URI.create(
          "https://blocked.example/" + address.replace(':', '-'))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
