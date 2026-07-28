package dev.christopherbell.music.catalog;

import java.time.Clock;
import dev.christopherbell.music.radio.MusicRadioProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Wires the bounded Music indexing pipeline. */
@Configuration
@EnableConfigurationProperties({MusicProperties.class, MusicRadioProperties.class})
public class MusicCatalogConfiguration {

  @Bean
  public MusicProcessRunner musicProcessRunner(MusicProperties properties) {
    return new JdkMusicProcessRunner(
        properties.probeTimeout(), properties.probeMaxOutputBytes());
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
      MusicArtworkService artwork) {
    return new MusicCatalogReconciler(
        properties, tracks, probe, artwork, Clock.systemUTC());
  }

  @Bean
  public MusicCatalog musicCatalog(MongoTemplate mongo, MusicTrackRepository tracks) {
    return new MusicCatalog(mongo, tracks);
  }
}
