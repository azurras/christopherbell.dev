package dev.christopherbell.federation.discovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Allowlisted public JSON documents for local federation discovery. */
public final class FederationDiscoveryModels {
  private FederationDiscoveryModels() {}

  public record WebFingerDocument(
      String subject,
      List<String> aliases,
      List<WebFingerLink> links) {}

  public record WebFingerLink(String rel, String type, String href) {}

  public record NodeInfoDiscovery(List<NodeInfoLink> links) {}

  public record NodeInfoLink(String rel, String href) {}

  public record NodeInfoDocument(
      String version,
      NodeInfoSoftware software,
      List<String> protocols,
      NodeInfoServices services,
      boolean openRegistrations,
      NodeInfoUsage usage,
      Map<String, Object> metadata) {}

  public record NodeInfoSoftware(String name, String version) {}

  public record NodeInfoServices(List<String> inbound, List<String> outbound) {}

  public record NodeInfoUsage(NodeInfoUsers users, long localPosts) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record NodeInfoUsers(Long total, Long activeMonth, Long activeHalfyear) {}

  public record ActivityPubActor(
      @JsonProperty("@context") List<String> context,
      String id,
      String type,
      String preferredUsername,
      String name,
      String inbox,
      String outbox,
      String followers,
      String following,
      String url,
      ActivityPubPublicKey publicKey) {}

  public record ActivityPubPublicKey(String id, String owner, String publicKeyPem) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivityPubOrderedCollection<T>(
      @JsonProperty("@context") List<String> context,
      String id,
      String type,
      long totalItems,
      String first,
      String partOf,
      List<T> orderedItems,
      String next) {}

  public record ActivityPubCreate(
      String id,
      String type,
      String actor,
      Instant published,
      List<String> to,
      List<String> cc,
      ActivityPubNote object) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivityPubNote(
      String id,
      String type,
      String attributedTo,
      String content,
      Instant published,
      Instant updated,
      String inReplyTo,
      List<String> to,
      List<String> cc,
      String url) {}
}
