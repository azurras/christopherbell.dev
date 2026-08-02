package dev.christopherbell.music.catalog;

import java.time.Clock;
import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorCoordinator;
import dev.christopherbell.music.metadata.FfmpegMusicTagProcess;
import dev.christopherbell.music.metadata.MusicMetadataEditRepository;
import dev.christopherbell.music.metadata.MusicMetadataFileStore;
import dev.christopherbell.music.metadata.MusicMetadataProperties;
import dev.christopherbell.music.metadata.MusicMetadataService;
import dev.christopherbell.music.metadata.MusicTagProcess;
import dev.christopherbell.music.radio.MusicRadioProperties;
import dev.christopherbell.music.security.MusicAccessService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Wires the bounded Music indexing pipeline. */
@Configuration
@EnableConfigurationProperties({
    MusicProperties.class, MusicMediaToolProperties.class, MusicRadioProperties.class,
    MusicMetadataProperties.class
})
public class MusicCatalogConfiguration {

  @Bean
  public MusicExecutableResolver musicExecutableResolver(
      MusicProperties music,
      MusicMediaToolProperties mediaTools,
      ObjectMapper objectMapper) {
    return new MusicExecutableResolver(
        music.enabled(), music.ffmpegCommand(), music.ffprobeCommand(), mediaTools, objectMapper);
  }

  @Bean
  public MusicProcessRunner musicProcessRunner(
      MusicProperties properties,
      MusicExecutableResolver executables) {
    return new JdkMusicProcessRunner(
        properties.probeTimeout(), properties.probeMaxOutputBytes(), executables);
  }

  @Bean
  public MusicProbe musicProbe(
      MusicProperties properties,
      MusicProcessRunner runner,
      ObjectMapper objectMapper) {
    return new FfprobeMusicProbe(properties, runner, objectMapper);
  }

  @Bean
  public MusicArtworkService musicArtworkService(
      MusicProperties properties,
      MusicProcessRunner runner) {
    return new MusicArtworkService(properties, runner);
  }

  @Bean
  public MusicCatalogReconciler musicCatalogReconciler(
      MusicProperties properties,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicArtworkService artwork,
      ScheduledCollectorCoordinator scheduledCollectors) {
    return new MusicCatalogReconciler(
        properties, tracks, probe, artwork, scheduledCollectors, Clock.systemUTC());
  }

  @Bean
  public MusicCatalog musicCatalog(MongoTemplate mongo, MusicTrackRepository tracks) {
    return new MusicCatalog(mongo, tracks);
  }

  @Bean
  public MusicMetadataFileStore musicMetadataFileStore(
      MusicMetadataProperties properties,
      MusicProperties music) {
    return new MusicMetadataFileStore(properties, music.root());
  }

  @Bean
  public MusicTagProcess musicTagProcess(
      MusicProperties music,
      MusicMetadataProperties metadata,
      MusicExecutableResolver executables) {
    return new FfmpegMusicTagProcess(
        music,
        new JdkMusicProcessRunner(
            metadata.processTimeout(), metadata.processMaxOutputBytes(), executables));
  }

  @Bean
  public MusicMetadataService musicMetadataService(
      MusicProperties music,
      MusicMetadataProperties metadata,
      MusicCatalog catalog,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicArtworkService artwork,
      MusicTagProcess tagProcess,
      MusicMetadataFileStore files,
      MusicMetadataEditRepository edits,
      MusicAccessService access,
      MongoLeaseService leases,
      ScheduledCollectorCoordinator scheduledCollectors) {
    return new MusicMetadataService(
        music, metadata, catalog, tracks, probe, artwork, tagProcess, files, edits, access,
        leases, scheduledCollectors, Clock.systemUTC());
  }
}
