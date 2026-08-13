package dev.christopherbell.federation.outbound;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubCreate;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubNote;
import dev.christopherbell.federation.discovery.FederationOutboxEntry;
import dev.christopherbell.post.model.Post;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Owns stable public Create/Note construction for outbox reads and delivery. */
@Component
public final class FederationActivityFactory {
  private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

  private final String publicOrigin;

  public FederationActivityFactory(BrowserSecurityProperties browserSecurity) {
    this.publicOrigin = Objects.requireNonNull(browserSecurity, "browserSecurity")
        .publicBaseUrl()
        .toString();
  }

  public ActivityPubCreate create(String actorId, Post post) {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("Federation activity actor ID must not be blank");
    }
    Objects.requireNonNull(post, "post");
    if (post.getId() == null || post.getId().isBlank()) {
      throw new IllegalArgumentException("Federation activity post ID must not be blank");
    }
    return create(actorId, new FederationOutboxEntry(
        post.getId(),
        post.getText(),
        post.getParentId(),
        post.getCreatedOn(),
        post.getLastUpdatedOn()));
  }

  public ActivityPubCreate create(String actorId, FederationOutboxEntry entry) {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("Federation activity actor ID must not be blank");
    }
    Objects.requireNonNull(entry, "entry");
    if (entry.id() == null || entry.id().isBlank()) {
      throw new IllegalArgumentException("Federation activity post ID must not be blank");
    }
    String objectId = publicOrigin + "/void/" + entry.id();
    List<String> to = List.of(PUBLIC);
    List<String> cc = List.of(actorId + "/followers");
    String reply = entry.parentId() == null
        ? null
        : publicOrigin + "/void/" + entry.parentId();
    var note = new ActivityPubNote(
        objectId,
        "Note",
        actorId,
        HtmlUtils.htmlEscape(String.valueOf(entry.text() == null ? "" : entry.text())),
        entry.createdOn(),
        entry.lastUpdatedOn(),
        reply,
        to,
        cc,
        objectId);
    return new ActivityPubCreate(
        objectId + "#activity",
        "Create",
        actorId,
        entry.createdOn(),
        to,
        cc,
        note);
  }
}
