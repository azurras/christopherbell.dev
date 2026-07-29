package dev.christopherbell.post.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class VoidDiscoveryRepositoryProxyCompatibilityTest {

  @Test
  void discoveryRepositoriesRemainProxyableForSpringExceptionTranslation() {
    assertProxyable(VoidDiscoveryQueryRepository.class);
    assertProxyable(VoidPeopleDiscoveryQueryRepository.class);
  }

  private static void assertProxyable(Class<?> repositoryType) {
    assertFalse(
        Modifier.isFinal(repositoryType.getModifiers()),
        () -> repositoryType.getSimpleName() + " must remain proxyable by Spring");
  }
}
