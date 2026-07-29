package dev.christopherbell.federation.outbound;

import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves and pins one configured peer address while rejecting SSRF destinations. */
final class FederationPeerAddressPolicy {
  private final AddressResolver resolver;

  FederationPeerAddressPolicy() {
    this(host -> Arrays.asList(InetAddress.getAllByName(host)));
  }

  FederationPeerAddressPolicy(AddressResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  ValidatedPeerTarget validateAndResolve(
      ControlledPeer peer,
      URI publicOrigin,
      boolean developmentLoopbackEnabled
  ) {
    Objects.requireNonNull(peer, "peer");
    Objects.requireNonNull(publicOrigin, "publicOrigin");
    URI inbox = peer.inbox();
    String scheme = normalize(inbox.getScheme());
    String host = inbox.getHost();
    boolean loopbackDevelopment = developmentLoopbackEnabled
        && "http".equals(scheme)
        && isLoopbackOrigin(publicOrigin);
    requireBoundedInbox(inbox, scheme, host, loopbackDevelopment);

    List<InetAddress> addresses = resolveAll(host);
    boolean addressesAllowed = loopbackDevelopment
        ? addresses.stream().allMatch(FederationPeerAddressPolicy::isLoopback)
        : addresses.stream().allMatch(FederationPeerAddressPolicy::isGlobal);
    if (!addressesAllowed) {
      throw new IllegalArgumentException(
          "Federation outbound peer resolved to an unsafe network address");
    }
    InetAddress selected = addresses.stream()
        .sorted(Comparator
            .comparingInt((InetAddress address) -> address.getAddress().length)
            .thenComparing(FederationPeerAddressPolicy::addressKey))
        .findFirst()
        .orElseThrow();
    int port = inbox.getPort() == -1 ? 443 : inbox.getPort();
    return new ValidatedPeerTarget(inbox, host.toLowerCase(Locale.ROOT),
        new InetSocketAddress(selected, port));
  }

  private List<InetAddress> resolveAll(String host) {
    final List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException failure) {
      throw new IllegalArgumentException("Federation outbound peer could not be resolved", failure);
    }
    if (addresses == null || addresses.isEmpty() || addresses.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Federation outbound peer did not resolve to an address");
    }
    return List.copyOf(addresses);
  }

  private static void requireBoundedInbox(
      URI inbox,
      String scheme,
      String host,
      boolean loopbackDevelopment
  ) {
    boolean validHttps = "https".equals(scheme)
        && (inbox.getPort() == -1 || inbox.getPort() == 443);
    boolean validDevelopmentHttp = loopbackDevelopment
        && inbox.getPort() >= 1
        && inbox.getPort() <= 65_535;
    String path = inbox.getRawPath();
    if ((!validHttps && !validDevelopmentHttp)
        || host == null
        || host.isBlank()
        || inbox.getUserInfo() != null
        || inbox.getRawQuery() != null
        || inbox.getRawFragment() != null
        || path == null
        || path.isBlank()
        || "/".equals(path)) {
      throw new IllegalArgumentException(
          "Federation outbound peer inbox must be a bounded HTTPS inbox URL");
    }
  }

  private static boolean isLoopbackOrigin(URI publicOrigin) {
    if (!"http".equals(normalize(publicOrigin.getScheme()))
        || publicOrigin.getHost() == null
        || publicOrigin.getUserInfo() != null
        || publicOrigin.getQuery() != null
        || publicOrigin.getFragment() != null) {
      return false;
    }
    try {
      return InetAddress.getByName(publicOrigin.getHost()).isLoopbackAddress();
    } catch (UnknownHostException failure) {
      return false;
    }
  }

  private static boolean isLoopback(InetAddress address) {
    return address != null && address.isLoopbackAddress();
  }

  private static boolean isGlobal(InetAddress address) {
    if (address == null
        || address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    if (address instanceof Inet4Address) {
      return isGlobalIpv4(address.getAddress());
    }
    if (address instanceof Inet6Address) {
      return isGlobalIpv6(address.getAddress());
    }
    return false;
  }

  private static boolean isGlobalIpv4(byte[] address) {
    int first = Byte.toUnsignedInt(address[0]);
    int second = Byte.toUnsignedInt(address[1]);
    int third = Byte.toUnsignedInt(address[2]);
    return first != 0
        && first != 10
        && !(first == 100 && second >= 64 && second <= 127)
        && first != 127
        && !(first == 169 && second == 254)
        && !(first == 172 && second >= 16 && second <= 31)
        && !(first == 192 && second == 0 && third == 0)
        && !(first == 192 && second == 0 && third == 2)
        && !(first == 192 && second == 168)
        && !(first == 198 && (second == 18 || second == 19))
        && !(first == 198 && second == 51 && third == 100)
        && !(first == 203 && second == 0 && third == 113)
        && first < 224;
  }

  private static boolean isGlobalIpv6(byte[] address) {
    int first = Byte.toUnsignedInt(address[0]);
    int second = Byte.toUnsignedInt(address[1]);
    int third = Byte.toUnsignedInt(address[2]);
    int fourth = Byte.toUnsignedInt(address[3]);
    boolean globallyRoutable = (first & 0xe0) == 0x20;
    boolean documentation = first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8;
    return globallyRoutable && !documentation;
  }

  private static String addressKey(InetAddress address) {
    return HexFormat.of().formatHex(address.getAddress());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  record ValidatedPeerTarget(URI inbox, String originalHost, InetSocketAddress remoteAddress) {
    ValidatedPeerTarget {
      Objects.requireNonNull(inbox, "inbox");
      Objects.requireNonNull(originalHost, "originalHost");
      Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
  }

  @FunctionalInterface
  interface AddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }
}
