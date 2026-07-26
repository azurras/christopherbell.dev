package dev.christopherbell.account.deletion;

import dev.christopherbell.sharedfolder.media.MediaPlaybackService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Routes account deletion through shared-folder services that own private file semantics. */
@Component
@RequiredArgsConstructor
public class SharedFolderAccountDeletionResourceCleaner
    implements AccountDeletionResourceCleaner {
  private final SharedFolderUploadService uploads;
  private final MediaPlaybackService media;
  private final SharedFolderMutationService mutations;

  @Override
  public void deleteOwnedResources(String accountId) {
    mutations.deleteOwnedPrivateState(accountId);
    uploads.deleteOwnedPrivateState(accountId);
    media.deleteOwnedPrivateState(accountId);
  }
}
