package dev.christopherbell.location.zip;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.location.model.ZipCoordinate;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns ZIP coordinate lookup and Census dataset refresh behavior.
 */
@RequiredArgsConstructor
@Service
public class ZipCoordinateService {
  private static final String CENSUS_STATE_ID = "census-zcta";
  private static final Pattern FIVE_DIGIT_ZIP = Pattern.compile("\\d{5}");
  private static final Pattern ZIP_PLUS_FOUR = Pattern.compile("\\d{5}-\\d{4}");

  private final Clock clock;
  private final ZipCoordinateGazetteerReader zipCoordinateGazetteerReader;
  private final ZipCoordinateImportStateRepository zipCoordinateImportStateRepository;
  private final ZipCoordinateRepository zipCoordinateRepository;

  /**
   * Imports or refreshes the bundled Census ZIP coordinates in MongoDB.
   *
   * <p>An unchanged dataset checksum returns a no-op result without touching coordinates.
   * Otherwise new ZIP codes are created, changed ones updated in place, and Census ZIP codes
   * missing from the dataset deleted, then the import state is recorded.</p>
   *
   * @return import result counts
   */
  public ZipCoordinateImportResult importCensusZipCoordinates() {
    List<ZipCoordinate> importedCoordinates = zipCoordinateGazetteerReader.readBundledCensusData();
    String checksum = datasetChecksum(importedCoordinates);
    Optional<ZipCoordinateImportState> previousImport =
        zipCoordinateImportStateRepository.findById(CENSUS_STATE_ID);
    if (previousImport.map(ZipCoordinateImportState::getChecksum).filter(checksum::equals).isPresent()) {
      return unchangedDatasetResult(importedCoordinates.size(), checksum, previousImport.get());
    }

    CensusImportPlan importPlan = planCensusImport(importedCoordinates);
    if (!importPlan.coordinatesToSave().isEmpty()) {
      zipCoordinateRepository.saveAll(importPlan.coordinatesToSave());
    }
    if (!importPlan.staleCoordinates().isEmpty()) {
      zipCoordinateRepository.deleteAll(importPlan.staleCoordinates());
    }

    Instant importedOn = Instant.now(clock);
    ZipCoordinateImportResult importResult = ZipCoordinateImportResult.builder()
        .processed(importedCoordinates.size())
        .created(importPlan.createdCount())
        .updated(importPlan.updatedCount())
        .unchanged(importPlan.unchangedCount())
        .deleted(importPlan.staleCoordinates().size())
        .source(ZipCoordinateGazetteerReader.CENSUS_SOURCE)
        .sourceYear(ZipCoordinateGazetteerReader.CENSUS_SOURCE_YEAR)
        .checksum(checksum)
        .importedOn(importedOn)
        .noOp(false)
        .build();
    zipCoordinateImportStateRepository.save(ZipCoordinateImportState.builder()
        .id(CENSUS_STATE_ID)
        .checksum(checksum)
        .source(ZipCoordinateGazetteerReader.CENSUS_SOURCE)
        .sourceYear(ZipCoordinateGazetteerReader.CENSUS_SOURCE_YEAR)
        .importedOn(importedOn)
        .result(importResult)
        .build());
    return importResult;
  }

  /**
   * Produces a SHA-256 checksum of a coordinate dataset that is independent of row order.
   *
   * @param coordinates the dataset rows
   * @return the lowercase hex checksum
   */
  public static String datasetChecksum(List<ZipCoordinate> coordinates) {
    List<String> sortedRowFingerprints = coordinates.stream()
        .map(coordinate -> "%s|%s|%s|%s|%s".formatted(
            coordinate.getZipCode(),
            coordinate.getLatitude(),
            coordinate.getLongitude(),
            coordinate.getSource(),
            coordinate.getSourceYear()))
        .sorted()
        .toList();
    MessageDigest sha256 = sha256Digest();
    for (String rowFingerprint : sortedRowFingerprints) {
      sha256.update((rowFingerprint + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(sha256.digest());
  }

  /**
   * Finds the imported coordinate origin for a ZIP or ZIP+4 code.
   *
   * @param requestedZipCode ZIP input as the caller sent it
   * @return public ZIP coordinate detail
   * @throws InvalidRequestException when the input is not a ZIP or ZIP+4 code
   * @throws ResourceNotFoundException when imported Location data has no coordinate for the ZIP
   */
  public ZipCoordinateDetail findCoordinateForZip(String requestedZipCode)
      throws InvalidRequestException, ResourceNotFoundException {
    String fiveDigitZipCode = fiveDigitZipCodeOf(requestedZipCode);
    return zipCoordinateRepository.findById(fiveDigitZipCode)
        .map(ZipCoordinateService::detailOf)
        .orElseThrow(() -> new ResourceNotFoundException(
            "ZIP coordinate not found: " + fiveDigitZipCode));
  }

  private CensusImportPlan planCensusImport(List<ZipCoordinate> importedCoordinates) {
    Map<String, ZipCoordinate> storedCoordinatesByZipCode = storedCensusCoordinatesByZipCode();
    List<ZipCoordinate> coordinatesToSave = new ArrayList<>();
    int createdCount = 0;
    int updatedCount = 0;
    int unchangedCount = 0;

    for (ZipCoordinate importedCoordinate : importedCoordinates) {
      ZipCoordinate storedCoordinate =
          storedCoordinatesByZipCode.remove(importedCoordinate.getZipCode());
      if (storedCoordinate == null) {
        coordinatesToSave.add(importedCoordinate);
        createdCount++;
      } else if (hasSameImportedValues(storedCoordinate, importedCoordinate)) {
        unchangedCount++;
      } else {
        copyImportedValues(importedCoordinate, storedCoordinate);
        coordinatesToSave.add(storedCoordinate);
        updatedCount++;
      }
    }

    List<ZipCoordinate> staleCoordinates = List.copyOf(storedCoordinatesByZipCode.values());
    return new CensusImportPlan(
        List.copyOf(coordinatesToSave), staleCoordinates, createdCount, updatedCount, unchangedCount);
  }

  private Map<String, ZipCoordinate> storedCensusCoordinatesByZipCode() {
    Map<String, ZipCoordinate> coordinatesByZipCode = new LinkedHashMap<>();
    List<ZipCoordinate> storedCoordinates =
        zipCoordinateRepository.findAllBySource(ZipCoordinateGazetteerReader.CENSUS_SOURCE);
    for (ZipCoordinate storedCoordinate : storedCoordinates) {
      coordinatesByZipCode.put(storedCoordinate.getZipCode(), storedCoordinate);
    }
    return coordinatesByZipCode;
  }

  private static ZipCoordinateImportResult unchangedDatasetResult(
      int processedCount, String checksum, ZipCoordinateImportState previousImport) {
    return ZipCoordinateImportResult.builder()
        .processed(processedCount)
        .unchanged(processedCount)
        .source(ZipCoordinateGazetteerReader.CENSUS_SOURCE)
        .sourceYear(ZipCoordinateGazetteerReader.CENSUS_SOURCE_YEAR)
        .checksum(checksum)
        .importedOn(previousImport.getImportedOn())
        .noOp(true)
        .build();
  }

  private static boolean hasSameImportedValues(
      ZipCoordinate storedCoordinate, ZipCoordinate importedCoordinate) {
    return Double.compare(storedCoordinate.getLatitude(), importedCoordinate.getLatitude()) == 0
        && Double.compare(storedCoordinate.getLongitude(), importedCoordinate.getLongitude()) == 0
        && storedCoordinate.getSourceYear() == importedCoordinate.getSourceYear()
        && importedCoordinate.getSource().equals(storedCoordinate.getSource());
  }

  /** Overwrites the stored row's dataset values in place, keeping its id and audit dates. */
  private static void copyImportedValues(
      ZipCoordinate importedCoordinate, ZipCoordinate storedCoordinate) {
    storedCoordinate.setLatitude(importedCoordinate.getLatitude());
    storedCoordinate.setLongitude(importedCoordinate.getLongitude());
    storedCoordinate.setSource(importedCoordinate.getSource());
    storedCoordinate.setSourceYear(importedCoordinate.getSourceYear());
  }

  private static ZipCoordinateDetail detailOf(ZipCoordinate coordinate) {
    return ZipCoordinateDetail.builder()
        .zipCode(coordinate.getZipCode())
        .latitude(coordinate.getLatitude())
        .longitude(coordinate.getLongitude())
        .source(coordinate.getSource())
        .sourceYear(coordinate.getSourceYear())
        .build();
  }

  private static String fiveDigitZipCodeOf(String requestedZipCode) throws InvalidRequestException {
    String trimmedZipCode = requestedZipCode == null ? "" : requestedZipCode.strip();
    if (FIVE_DIGIT_ZIP.matcher(trimmedZipCode).matches()) {
      return trimmedZipCode;
    }
    if (ZIP_PLUS_FOUR.matcher(trimmedZipCode).matches()) {
      return trimmedZipCode.substring(0, 5);
    }
    throw new InvalidRequestException("ZIP code must be a valid 5-digit US ZIP code.");
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** What a Census refresh will write, computed before any repository change. */
  private record CensusImportPlan(
      List<ZipCoordinate> coordinatesToSave,
      List<ZipCoordinate> staleCoordinates,
      int createdCount,
      int updatedCount,
      int unchangedCount) {
  }
}
