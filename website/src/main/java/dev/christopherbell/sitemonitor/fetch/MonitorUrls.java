package dev.christopherbell.sitemonitor.fetch;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Strict pilot URL shapes prevent credentials, query secrets and origin escape. */
public final class MonitorUrls {
  public static final String DEMO_ORIGIN = "https://www.christopherbell.dev";
  public static final List<String> DEMO_PATHS = List.of("/", "/zip-coordinates", "/vin-decoder");
  private MonitorUrls() {}

  public static URI origin(String value) {
    if (value == null || value.length() > 300) throw new IllegalArgumentException("Invalid site URL.");
    URI candidate = URI.create(value.strip());
    if (!"https".equals(candidate.getScheme()) || candidate.getHost() == null
        || candidate.getHost().contains(":") || candidate.getHost().endsWith(".")
        || candidate.getUserInfo() != null || candidate.getRawQuery() != null
        || candidate.getRawFragment() != null
        || (candidate.getPort() != -1 && candidate.getPort() != 443)
        || !(candidate.getRawPath().isEmpty() || candidate.getRawPath().equals("/"))) {
      throw new IllegalArgumentException("Use an HTTPS site origin, without a path or credentials.");
    }
    return URI.create("https://" + candidate.getHost().toLowerCase(Locale.ROOT));
  }

  public static List<String> paths(URI origin, List<String> values) {
    if (values == null || values.isEmpty() || values.size() > 5) {
      throw new IllegalArgumentException("Choose one to five page paths.");
    }
    return values.stream().map(value -> {
      if (value == null || value.length() > 300 || !value.startsWith("/")
          || value.startsWith("//") || value.contains("\\")) {
        throw new IllegalArgumentException("Use relative page paths starting with /.");
      }
      URI page = origin.resolve(value);
      if (!sameOrigin(origin, page) || page.getRawQuery() != null || page.getRawFragment() != null
          || !page.normalize().equals(page)
          || java.util.Arrays.stream(page.getPath().split("/"))
              .anyMatch(segment -> segment.equals(".") || segment.equals(".."))
          || page.getPath().contains("\\")) {
        throw new IllegalArgumentException("Page paths cannot contain queries, fragments or traversal.");
      }
      return page.getRawPath();
    }).distinct().toList();
  }

  public static boolean sameOrigin(URI origin, URI candidate) {
    return candidate != null && "https".equals(candidate.getScheme())
        && candidate.getHost() != null && candidate.getHost().equalsIgnoreCase(origin.getHost())
        && (candidate.getPort() == -1 || candidate.getPort() == 443)
        && candidate.getUserInfo() == null && candidate.getRawFragment() == null;
  }
}
