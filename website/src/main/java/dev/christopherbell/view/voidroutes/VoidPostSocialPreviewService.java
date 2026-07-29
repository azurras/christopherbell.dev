package dev.christopherbell.view.voidroutes;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves active public posts before deriving externally visible social metadata. */
@RequiredArgsConstructor
@Service
public class VoidPostSocialPreviewService {
  private final PostService posts;
  private final Clock clock;

  /** Returns preview metadata only when the post domain still considers the post active. */
  public VoidPostSocialPreview preview(String postId) throws ResourceNotFoundException {
    return VoidPostSocialPreview.from(posts.getPostById(postId), clock.instant());
  }
}
