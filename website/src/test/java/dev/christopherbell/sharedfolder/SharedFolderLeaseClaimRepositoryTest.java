package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.sharedfolder.service.MongoSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Version;

class SharedFolderLeaseClaimRepositoryTest {
  @Test
  void expiredLeaseClaimsUseVersionedEntitiesAndExplicitKindScopedAdapters() throws Exception {
    assertThat(SharedFolderMutationRecovery.class.getDeclaredField("version")
        .isAnnotationPresent(Version.class)).isTrue();
    assertThat(SharedFolderUploadSession.class.getDeclaredField("version")
        .isAnnotationPresent(Version.class)).isTrue();
    assertThat(SharedFolderMutationRecoveryRepository.class.getDeclaredMethod(
        "claimExpiredOperationLease", String.class, String.class,
        dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState.class,
        java.time.Instant.class, String.class, java.time.Instant.class, java.time.Instant.class))
        .isNotNull();
    assertThat(SharedFolderUploadSessionRepository.class.getDeclaredMethod(
        "claimExpiredAppendLease", String.class, String.class, long.class,
        java.time.Instant.class, String.class, java.time.Instant.class, java.time.Instant.class))
        .isNotNull();
    assertThat(SharedFolderUploadSessionRepository.class.getDeclaredMethod(
        "claimExpiredFinalizationLease", String.class, String.class,
        dev.christopherbell.sharedfolder.upload.SharedFolderUploadFinalizationState.class,
        java.time.Instant.class, String.class, java.time.Instant.class, java.time.Instant.class))
        .isNotNull();
    assertThat(MongoSharedFolderMutationRecoveryRepository.class.getInterfaces())
        .contains(SharedFolderMutationRecoveryRepository.class);
    assertThat(MongoSharedFolderUploadSessionRepository.class.getInterfaces())
        .contains(SharedFolderUploadSessionRepository.class);
  }
}
