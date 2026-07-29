package dev.christopherbell.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers validated shared-folder configuration without creating a filesystem operation bean. */
@Configuration
@EnableConfigurationProperties({
    SharedFolderProperties.class,
    SharedFolderMediaProperties.class,
    SharedFolderCatalogProperties.class
})
public class SharedFolderConfiguration {
  /** Owns the sole bounded catalog worker outside servlet request threads. */
  @Bean(destroyMethod = "shutdownNow")
  @Qualifier("sharedFolderCatalogExecutor")
  public ExecutorService sharedFolderCatalogExecutor() {
    return Executors.newSingleThreadExecutor(Thread.ofPlatform()
        .daemon(true)
        .name("shared-folder-catalog-", 0)
        .factory());
  }
}
