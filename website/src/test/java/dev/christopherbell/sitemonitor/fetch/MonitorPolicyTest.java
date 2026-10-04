package dev.christopherbell.sitemonitor.fetch;

import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonitorPolicyTest {
  @ParameterizedTest
  @ValueSource(strings = { "http://example.com", "https://example.com:8443", "https://user:pass@example.com",
      "https://example.com/path", "https://example.com?q=secret", "https://example.com#part",
      "https://[::1]", "https://example.com." })
  void rejectsUnsupportedOriginShapes(String value) {
    assertThatThrownBy(() -> MonitorUrls.origin(value)).isInstanceOf(IllegalArgumentException.class);
  }
  @ParameterizedTest
  @ValueSource(strings = { "//other.example/", "/../secret", "/a?secret=value", "/a#part", "/\\other" })
  void pathsCannotEscapeOrRetainQuerySecrets(String path) {
    assertThatThrownBy(() -> MonitorUrls.paths(URI.create("https://example.com"), List.of(path)))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @ParameterizedTest
  @ValueSource(strings = { "127.0.0.1", "10.0.0.1", "169.254.169.254", "192.168.1.1", "172.16.0.1",
      "100.64.0.1", "0.0.0.0", "198.18.0.1", "192.0.2.1", "224.0.0.1", "::1", "fc00::1", "2002::1" })
  void blocksPrivateReservedAndTransitionDnsAnswers(String address) throws Exception {
    var policy = new SiteMonitorDestinationPolicy(host -> List.of(InetAddress.getByName(address)));
    assertThatThrownBy(() -> policy.resolveApproved(URI.create("https://example.com"), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test
  void rejectsMixedDnsAndPinsTheOnlyApprovedAnswer() throws Exception {
    var publicIp = InetAddress.getByName("8.8.8.8");
    var policy = new SiteMonitorDestinationPolicy(host -> List.of(publicIp, InetAddress.getByName("127.0.0.1")));
    assertThatThrownBy(() -> policy.resolveApproved(URI.create("https://example.com"), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    var destination = new SiteMonitorDestinationPolicy(host -> List.of(publicIp))
        .resolveApproved(URI.create("https://example.com"), Duration.ofSeconds(1));
    assertThat(destination.remoteAddress().getAddress()).isEqualTo(publicIp);
    assertThat(destination.remoteAddress().getPort()).isEqualTo(443);
  }
}
