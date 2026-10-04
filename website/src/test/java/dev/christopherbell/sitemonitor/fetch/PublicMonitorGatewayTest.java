package dev.christopherbell.sitemonitor.fetch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PublicMonitorGatewayTest {
  final URI origin = URI.create("https://example.com");
  final SiteMonitorDestinationPolicy policy = mock(SiteMonitorDestinationPolicy.class);
  final MonitorHttpTransport transport = mock(MonitorHttpTransport.class);

  @Test void guardAfterDnsStopsBeforeConnectingWhenOwnershipIsLost() throws Exception {
    var address = InetAddress.getByName("8.8.8.8");
    when(policy.resolveApproved(any(), any())).thenReturn(new SiteMonitorDestinationPolicy.ApprovedDestination(
        origin, origin.getHost(), List.of(address), new InetSocketAddress(address, 443)));
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> new PublicMonitorGateway(policy, transport).fetch(origin, origin,
        System.nanoTime() + 1_000_000_000L, false, () -> {
          if (calls.incrementAndGet() == 2) throw new IllegalStateException("lease lost");
        })).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(transport);
  }
  @Test void redirectCannotEscapeOriginAndNeverConnectsToExternalSite() throws Exception {
    var address = InetAddress.getByName("8.8.8.8");
    when(policy.resolveApproved(any(), any())).thenReturn(new SiteMonitorDestinationPolicy.ApprovedDestination(
        origin, origin.getHost(), List.of(address), new InetSocketAddress(address, 443)));
    when(transport.get(any(), any(), anyMap(), anyInt(), anyList(), anyBoolean()))
        .thenReturn(new MonitorHttpTransport.Response(302, Map.of("location", List.of("https://outside.example/")), new byte[0]));
    assertThatThrownBy(() -> new PublicMonitorGateway(policy, transport).fetch(origin, origin,
        System.nanoTime() + 1_000_000_000L, false)).isInstanceOf(MonitorFetchException.class)
        .extracting("category").isEqualTo("REDIRECT_OUTSIDE_SITE");
    verify(policy).resolveApproved(eq(origin), any());
    verify(transport).get(any(), any(), anyMap(), anyInt(), anyList(), anyBoolean());
  }
}
