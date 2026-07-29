package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.post.model.Post;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FederationActivityFactoryTest {
  private static final String ORIGIN = "https://www.christopherbell.dev";
  private static final String ACTOR = ORIGIN + "/ap/users/chris";

  @Test
  void createsStableEscapedPublicActivityForRootPost() {
    var factory = factory();
    var post = Post.builder()
        .id("post-1")
        .text("hello <script>alert(1)</script>")
        .createdOn(Instant.parse("2026-07-28T12:00:00Z"))
        .lastUpdatedOn(Instant.parse("2026-07-28T12:01:00Z"))
        .build();

    var first = factory.create(ACTOR, post);
    var second = factory.create(ACTOR, post);

    assertThat(first).isEqualTo(second);
    assertThat(first.id()).isEqualTo(ORIGIN + "/void/post-1#activity");
    assertThat(first.object().id()).isEqualTo(ORIGIN + "/void/post-1");
    assertThat(first.object().content())
        .isEqualTo("hello &lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(first.object().inReplyTo()).isNull();
    assertThat(first.to()).containsExactly("https://www.w3.org/ns/activitystreams#Public");
    assertThat(first.cc()).containsExactly(ACTOR + "/followers");
    assertThat(first.object().to()).isEqualTo(first.to());
    assertThat(first.object().cc()).isEqualTo(first.cc());
  }

  @Test
  void linksRepliesToTheirStablePublicObject() {
    var post = Post.builder()
        .id("reply-1")
        .parentId("parent-1")
        .text(null)
        .createdOn(Instant.parse("2026-07-28T12:00:00Z"))
        .build();

    var activity = factory().create(ACTOR, post);

    assertThat(activity.object().content()).isEmpty();
    assertThat(activity.object().inReplyTo()).isEqualTo(ORIGIN + "/void/parent-1");
  }

  private static FederationActivityFactory factory() {
    return new FederationActivityFactory(
        new BrowserSecurityProperties(URI.create(ORIGIN), true, true));
  }
}
