package dev.christopherbell.configuration.persistence.migration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Versioned, bounded binary codec for typed staged column values. */
final class MigrationRowCodec {
  private static final int VERSION = 1;
  private static final int MAX_VALUES = 512;
  private static final int MAX_BYTES = 16 * 1024 * 1024;

  byte[] encode(Map<String, Object> values) {
    if (values.size() > MAX_VALUES) {
      throw invalid();
    }
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(VERSION);
        output.writeInt(values.size());
        for (var entry : values.entrySet()) {
          output.writeUTF(entry.getKey());
          writeValue(output, entry.getValue());
        }
      }
      if (bytes.size() > MAX_BYTES) {
        throw invalid();
      }
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw invalid();
    }
  }

  Map<String, Object> decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw invalid();
    }
    try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != VERSION) {
        throw invalid();
      }
      var size = input.readInt();
      if (size < 0 || size > MAX_VALUES) {
        throw invalid();
      }
      var values = new LinkedHashMap<String, Object>();
      for (var index = 0; index < size; index++) {
        var key = input.readUTF();
        if (values.put(key, readValue(input)) != null) {
          throw invalid();
        }
      }
      if (input.available() != 0) {
        throw invalid();
      }
      return values;
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static void writeValue(DataOutputStream output, Object value) throws IOException {
    switch (value) {
      case null -> output.writeByte(0);
      case String text -> {
        output.writeByte(1);
        output.writeUTF(text);
      }
      case Integer number -> {
        output.writeByte(2);
        output.writeInt(number);
      }
      case Long number -> {
        output.writeByte(3);
        output.writeLong(number);
      }
      case Boolean flag -> {
        output.writeByte(4);
        output.writeBoolean(flag);
      }
      case BigDecimal decimal -> {
        output.writeByte(5);
        output.writeUTF(decimal.toPlainString());
      }
      case Double number -> {
        if (!Double.isFinite(number)) {
          throw invalid();
        }
        output.writeByte(6);
        output.writeDouble(number);
      }
      case Instant instant -> {
        output.writeByte(7);
        output.writeLong(instant.getEpochSecond());
        output.writeInt(instant.getNano());
      }
      case LocalDate date -> {
        output.writeByte(8);
        output.writeLong(date.toEpochDay());
      }
      case byte[] binary -> {
        output.writeByte(9);
        output.writeInt(binary.length);
        output.write(binary);
      }
      case UUID uuid -> {
        output.writeByte(10);
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
      }
      default -> throw invalid();
    }
  }

  private static Object readValue(DataInputStream input) throws IOException {
    return switch (input.readUnsignedByte()) {
      case 0 -> null;
      case 1 -> input.readUTF();
      case 2 -> input.readInt();
      case 3 -> input.readLong();
      case 4 -> input.readBoolean();
      case 5 -> new BigDecimal(input.readUTF());
      case 6 -> finite(input.readDouble());
      case 7 -> Instant.ofEpochSecond(input.readLong(), input.readInt());
      case 8 -> LocalDate.ofEpochDay(input.readLong());
      case 9 -> input.readNBytes(boundedLength(input));
      case 10 -> new UUID(input.readLong(), input.readLong());
      default -> throw invalid();
    };
  }

  private static int boundedLength(DataInputStream input) throws IOException {
    var length = input.readInt();
    if (length < 0 || length > MAX_BYTES) {
      throw invalid();
    }
    return length;
  }

  private static double finite(double value) {
    if (!Double.isFinite(value)) {
      throw invalid();
    }
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("PostgreSQL migration staged row is invalid.");
  }
}
