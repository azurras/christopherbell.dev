package dev.christopherbell.federation.outbound;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubCreate;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubNote;
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
    String objectId = publicOrigin + "/void/" + post.getId();
    List<String> to = List.of(PUBLIC);
    List<String> cc = List.of(actorId + "/followers");
    String reply = post.getParentId() == null
        ? null
        : publicOrigin + "/void/" + post.getParentId();
    var note = new ActivityPubNote(
        objectId,
        "Note",
        actorId,
        HtmlUtils.htmlEscape(String.valueOf(post.getText() == null ? "" : post.getText())),
        post.getCreatedOn(),
        post.getLastUpdatedOn(),
        reply,
        to,
        cc,
        objectId);
    return new ActivityPubCreate(
        objectId + "#activity",
        "Create",
        actorId,
        post.getCreatedOn(),
        to,
        cc,
        note);
  }
}
