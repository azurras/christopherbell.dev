package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.christopherbell.post.model.PostTopic;
import dev.christopherbell.post.topic.PostTopicExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostTopicExtractorTest {

  private final PostTopicExtractor extractor = new PostTopicExtractor();

  @Test
  void extractsNormalizedUniqueTopicsInFirstAppearanceOrder() {
    var topics = extractor.extract(
        "Listening to #Music, #ＭＵＳＩＣ and #Café while reading #Java_25.");

    assertEquals(List.of(
        new PostTopic("music", "Music"),
        new PostTopic("café", "Café"),
        new PostTopic("java_25", "Java_25")), topics);
  }

  @Test
  void limitsStoredTopicsToFiveWithoutRejectingThePostText() {
    var topics = extractor.extract("#one #two #three #four #five #six #seven");

    assertEquals(List.of(
        new PostTopic("one", "one"),
        new PostTopic("two", "two"),
        new PostTopic("three", "three"),
        new PostTopic("four", "four"),
        new PostTopic("five", "five")), topics);
  }

  @Test
  void ignoresMalformedAndOverlongTopicsAtUnicodeCodePointBoundaries() {
    String fortyCodePoints = "é".repeat(40);
    String fortyOneCodePoints = fortyCodePoints + "x";
    String expandsPastLimitWhenLowercased = "İ".repeat(40);

    var topics = extractor.extract(
        "Ignore #" + fortyOneCodePoints + " and #🔥beat and #" + expandsPastLimitWhenLowercased
            + " but keep #" + fortyCodePoints + ".");

    assertEquals(List.of(new PostTopic(fortyCodePoints, fortyCodePoints)), topics);
  }
}
