package dev.christopherbell.post.editing;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostMapper;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.preview.PostLinkPreviewService;
import java.time.Clock;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Applies the ownership, time, expiry, preview, and audit policy for post edits. */
@Service
@RequiredArgsConstructor
public class PostEditingService {
  private static final String NOT_EDITABLE = "Post cannot be edited.";
  private final PostRepository posts;
  private final PostMapper mapper;
  private final PostLinkPreviewService previews;
  private final PostExpirationService expiration;
  private final PostEditingProperties properties;
  private final Clock clock;

  /** Replaces post text when the caller and post satisfy the complete edit policy. */
  public PostDetail edit(
      String postId,
      PostEditRequest request,
      String actorAccountId,
      boolean administrator
  ) throws InvalidRequestException, ResourceNotFoundException {
    if (postId == null || postId.isBlank() || request == null
        || request.text() == null || request.text().isBlank()) {
      throw new InvalidRequestException(NOT_EDITABLE);
    }
    var post = posts.findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException(NOT_EDITABLE));
    if (!administrator && !post.getAccountId().equals(actorAccountId)) {
      throw new ResourceNotFoundException(NOT_EDITABLE);
    }
    var now = clock.instant();
    if (post.getCreatedOn() == null
        || !now.isBefore(post.getCreatedOn().plus(properties.editWindow()))
        || expiration.isExpired(post)) {
      throw new InvalidRequestException(NOT_EDITABLE);
    }
    var text = request.text().strip();
    if (text.length() > 280) {
      throw new InvalidRequestException(NOT_EDITABLE);
    }
    var audit = new ArrayList<>(post.getEditAudit() == null
        ? java.util.List.of() : post.getEditAudit());
    audit.add(new PostEditAuditEvent(actorAccountId, post.getText(), text, now));
    int excess = audit.size() - properties.editAuditLimit();
    if (excess > 0) {
      audit.subList(0, excess).clear();
    }
    post.setText(text);
    post.setEditedOn(now);
    post.setEditAudit(audit);
    post.setLinkPreviews(previews.resolveForText(text));
    return mapper.toDetail(posts.save(post));
  }
}
