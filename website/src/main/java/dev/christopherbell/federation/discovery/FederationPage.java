package dev.christopherbell.federation.discovery;

import java.util.List;

/** One bounded stable-cursor page from a federation collection. */
public record FederationPage<T>(List<T> items, String nextCursor) {}
