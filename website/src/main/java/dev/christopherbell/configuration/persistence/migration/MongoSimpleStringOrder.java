package dev.christopherbell.configuration.persistence.migration;

import java.nio.charset.StandardCharsets;

/** MongoDB simple-collation ordering for source string identifiers. */
final class MongoSimpleStringOrder {
  private MongoSimpleStringOrder() {}

  static int compare(String left, String right) {
    var leftBytes = left.getBytes(StandardCharsets.UTF_8);
    var rightBytes = right.getBytes(StandardCharsets.UTF_8);
    var commonLength = Math.min(leftBytes.length, rightBytes.length);
    for (var index = 0; index < commonLength; index++) {
      var compared = Integer.compare(Byte.toUnsignedInt(leftBytes[index]),
          Byte.toUnsignedInt(rightBytes[index]));
      if (compared != 0) {
        return compared;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }
}
