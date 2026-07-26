package dev.christopherbell.post.preview;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
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
    if (uri == null
        || uri.getScheme() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getHost().isBlank()
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
    var first = Byte.toUnsignedInt(address[0]);
    var second = Byte.toUnsignedInt(address[1]);
    var third = Byte.toUnsignedInt(address[2]);
    var fourth = Byte.toUnsignedInt(address[3]);
    var globallyRoutable = (first & 0xe0) == 0x20;
    var documentation = first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8;
    return !globallyRoutable || documentation;
  }

  @FunctionalInterface
  public interface AddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }
}
