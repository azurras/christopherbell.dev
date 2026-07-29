package dev.christopherbell.view.voidroutes;

import dev.christopherbell.account.profile.AccountProfileService;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Builds public metadata from the same active-account projection used by the profile API. */
@RequiredArgsConstructor
@Service
public class VoidUserSocialPreviewService {
  private final AccountProfileService profiles;

  /** Returns safe public metadata for one active account. */
  public VoidUserSocialPreview preview(String username) throws ResourceNotFoundException {
    var profile = profiles.getPublicProfile(username);
    var handle = "@" + profile.username();
    var title = "CB | " + handle + " in the Void";
    var description = String.format(
        "%s has %d active posts and %d replies in the Void.",
        handle,
        profile.postCount(),
        profile.replyCount());
    var heroMetadata = String.format(
        "%d posts · %d replies", profile.postCount(), profile.replyCount());
    return new VoidUserSocialPreview(title, description, profile.username(), heroMetadata);
  }
}
