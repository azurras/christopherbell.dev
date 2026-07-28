package dev.christopherbell.music.radio;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MusicRadioConfiguration {
  @Bean
  MusicRadioSelector musicRadioSelector(MusicRadioProperties properties) {
    return new MusicRadioSelector(
        properties,
        () -> ThreadLocalRandom.current().nextDouble(),
        bound -> ThreadLocalRandom.current().nextInt(bound));
  }
}
