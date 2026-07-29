package dev.christopherbell.post.preview;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** Rejects link-preview destinations unless every resolved address is public. */
@Component
public class PostLinkPreviewDestinationPolicy {
  private final AddressResolver resolver;

  public PostLinkPreviewDestinationPolicy() {
    this(host -> Arrays.asList(InetAddress.getAllByName(host)));
  }

  public PostLinkPreviewDestinationPolicy(AddressResolver resolver) {
    this.resolver = resolver;
  }

  public void requirePublic(URI uri) {
    validateAndResolve(uri);
  }

  /** Resolve one hop once and bind it to an address from the validated answer set. */
  public ApprovedDestination resolveApproved(URI uri, Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new LinkPreviewFetchException("TIMEOUT");
    }
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    Future<ApprovedDestination> future = null;
    try {
      future = executor.submit(() -> validateAndResolve(uri));
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      throw new LinkPreviewFetchException("TIMEOUT", failure);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof IllegalArgumentException rejected) {
        throw rejected;
      }
      throw new IllegalArgumentException("Link preview destination resolution failed.",
          failure.getCause());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new LinkPreviewFetchException("INTERRUPTED", failure);
    } finally {
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }
  }

  private ApprovedDestination validateAndResolve(URI uri) {
    if (uri == null
        || uri.getScheme() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getHost().isBlank()
        || isUnsupportedHostIdentity(uri.getHost())
        || uri.getUserInfo() != null
        || "localhost".equalsIgnoreCase(uri.getHost())) {
      throw new IllegalArgumentException("Link preview destination must be a public HTTP(S) URL.");
    }

    final List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(uri.getHost());
    } catch (UnknownHostException failure) {
      throw new IllegalArgumentException("Link preview destination could not be resolved.", failure);
    }
    if (addresses == null || addresses.isEmpty() || addresses.stream().anyMatch(this::isBlocked)) {
      throw new IllegalArgumentException("Link preview destination resolved to a non-public address.");
    }
    var approvedAddresses = List.copyOf(addresses);
    var selected = approvedAddresses.stream()
        .sorted(Comparator
            .comparingInt((InetAddress address) -> address.getAddress().length)
            .thenComparing(PostLinkPreviewDestinationPolicy::addressKey))
        .findFirst()
        .orElseThrow();
    var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    var port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("Link preview destination port is invalid.");
    }
    return new ApprovedDestination(
        uri,
        uri.getHost().toLowerCase(Locale.ROOT),
        approvedAddresses,
        new InetSocketAddress(selected, port));
  }

  private boolean isBlocked(InetAddress address) {
    if (address == null
        || address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    if (address instanceof Inet4Address) {
      return isBlockedIpv4(address.getAddress());
    }
    if (address instanceof Inet6Address) {
      return isBlockedIpv6(address.getAddress());
    }
    return true;
  }

  private boolean isBlockedIpv4(byte[] address) {
    var first = Byte.toUnsignedInt(address[0]);
    var second = Byte.toUnsignedInt(address[1]);
    var third = Byte.toUnsignedInt(address[2]);
    return first == 0
        || first == 10
        || (first == 100 && second >= 64 && second <= 127)
        || first == 127
        || (first == 169 && second == 254)
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 0 && third == 0)
        || (first == 192 && second == 0 && third == 2)
        || (first == 192 && second == 168)
        || (first == 198 && (second == 18 || second == 19))
        || (first == 198 && second == 51 && third == 100)
        || (first == 203 && second == 0 && third == 113)
        || first >= 224;
  }

  private boolean isBlockedIpv6(byte[] address) {
    var first = hextet(address, 0);
    var second = hextet(address, 1);
    var globallyRoutable = (first & 0xe000) == 0x2000;
    var ietfProtocolAssignment = first == 0x2001 && second <= 0x01ff;
    var documentation = first == 0x2001 && second == 0x0db8;
    var sixToFour = first == 0x2002;
    var documentationV2 = first == 0x3fff && (second & 0xf000) == 0;
    return !globallyRoutable
        || (ietfProtocolAssignment && !isGloballyReachableIetfException(address))
        || documentation
        || sixToFour
        || documentationV2;
  }

  private boolean isGloballyReachableIetfException(byte[] address) {
    var second = hextet(address, 1);
    var third = hextet(address, 2);
    if (second == 0x0003 || (second == 0x0004 && third == 0x0112)) {
      return true;
    }
    if ((second & 0xfff0) == 0x0020 || (second & 0xfff0) == 0x0030) {
      return true;
    }
    if (second != 0x0001) {
      return false;
    }
    for (var index = 4; index < address.length - 1; index++) {
      if (address[index] != 0) {
        return false;
      }
    }
    var finalByte = Byte.toUnsignedInt(address[address.length - 1]);
    return finalByte >= 1 && finalByte <= 3;
  }

  private static int hextet(byte[] address, int index) {
    var offset = index * 2;
    return Byte.toUnsignedInt(address[offset]) << 8
        | Byte.toUnsignedInt(address[offset + 1]);
  }

  private static String addressKey(InetAddress address) {
    return HexFormat.of().formatHex(address.getAddress());
  }

  private static boolean isUnsupportedHostIdentity(String host) {
    return host.endsWith(".") || host.contains(":");
  }

  /** A destination whose remote address is provably one member of its validated DNS answers. */
  public record ApprovedDestination(
      URI uri,
      String originalHost,
      List<InetAddress> approvedAddresses,
      InetSocketAddress remoteAddress
  ) {
    public ApprovedDestination {
      Objects.requireNonNull(uri, "uri");
      Objects.requireNonNull(originalHost, "originalHost");
      approvedAddresses = List.copyOf(approvedAddresses);
      Objects.requireNonNull(remoteAddress, "remoteAddress");
      var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      var expectedPort = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
      if (originalHost.isBlank()
          || uri.getHost() == null
          || !originalHost.equalsIgnoreCase(uri.getHost())
          || !("http".equals(scheme) || "https".equals(scheme))
          || remoteAddress.isUnresolved()
          || remoteAddress.getPort() != expectedPort
          || !approvedAddresses.contains(remoteAddress.getAddress())) {
        throw new IllegalArgumentException(
            "Approved link preview address must belong to its validated DNS answer set.");
      }
    }
  }

  @FunctionalInterface
  public interface AddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }
}
