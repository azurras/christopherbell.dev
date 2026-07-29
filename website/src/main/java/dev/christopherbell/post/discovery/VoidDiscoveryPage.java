package dev.christopherbell.post.discovery;

import java.util.List;

/** A bounded public discovery page with an opaque continuation cursor. */
public record VoidDiscoveryPage<T>(List<T> items, String nextCursor) {
  public VoidDiscoveryPage {
    items = List.copyOf(items);
  }
}
