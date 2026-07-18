package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.repository.Update;

class SharedFolderLeaseClaimRepositoryTest {

  @Test
  void expiredLeaseClaimsInvalidateEveryStaleOptimisticVersion() throws Exception {
    assertVersionIncrement(SharedFolderMutationRecoveryRepository.class,
        "claimExpiredOperationLease");
    assertVersionIncrement(SharedFolderUploadSessionRepository.class,
        "claimExpiredAppendLease");
    assertVersionIncrement(SharedFolderUploadSessionRepository.class,
        "claimExpiredFinalizationLease");
  }

  private void assertVersionIncrement(Class<?> repository, String methodName) throws Exception {
    Method method = java.util.Arrays.stream(repository.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    assertThat(method.getAnnotation(Update.class).value())
        .as("%s must invalidate stale @Version snapshots", methodName)
        .contains("'$inc': { 'version': 1 }");
  }
}
