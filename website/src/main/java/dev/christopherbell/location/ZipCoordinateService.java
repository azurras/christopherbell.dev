package dev.christopherbell.location;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.location.model.ZipCoordinate;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns ZIP coordinate lookup and Census dataset refresh behavior.
 */
@RequiredArgsConstructor
@Service
public class ZipCoordinateService {
  private final ZipCoordinateGazetteerReader zipCoordinateGazetteerReader;
  private final ZipCoordinateRepository zipCoordinateRepository;

  /**
   * Imports or refreshes bundled Census ZIP coordinates in MongoDB.
   *
   * @return import result counts
   */
  public ZipCoordinateImportResult importCensusZipCoordinates() {
    var importedCoordinates = zipCoordinateGazetteerReader.readBundledCensusData();
    var existingByZipCode = existingCensusCoordinatesByZipCode();
    var changedCoordinates = new ArrayList<ZipCoordinate>();
    var created = 0;
    var updated = 0;
    var unchanged = 0;

    for (var importedCoordinate : importedCoordinates) {
      var existing = existingByZipCode.remove(importedCoordinate.getZipCode());
      if (existing == null) {
        changedCoordinates.add(importedCoordinate);
        created++;
      } else if (mergeChangedValues(existing, importedCoordinate)) {
        changedCoordinates.add(existing);
        updated++;
      } else {
        unchanged++;
      }
    }

    if (!changedCoordinates.isEmpty()) {
      zipCoordinateRepository.saveAll(changedCoordinates);
    }

    var staleCensusCoordinates = List.copyOf(existingByZipCode.values());
    if (!staleCensusCoordinates.isEmpty()) {
      zipCoordinateRepository.deleteAll(staleCensusCoordinates);
    }

    return ZipCoordinateImportResult.builder()
        .processed(importedCoordinates.size())
        .created(created)
        .updated(updated)
        .unchanged(unchanged)
        .deleted(staleCensusCoordinates.size())
        .source(ZipCoordinateGazetteerReader.CENSUS_SOURCE)
        .sourceYear(ZipCoordinateGazetteerReader.CENSUS_SOURCE_YEAR)
        .build();
  }

  /**
   * Finds a public ZIP coordinate by ZIP or ZIP+4 input.
   *
   * @param requestedZipCode ZIP input
   * @return public ZIP coordinate detail
   * @throws InvalidRequestException when the ZIP syntax is invalid
   * @throws ResourceNotFoundException when imported Location data has no coordinate for the ZIP
   */
  public ZipCoordinateDetail getZipCoordinate(String requestedZipCode)
      throws InvalidRequestException, ResourceNotFoundException {
    var zipCode = normalizeZipCode(requestedZipCode);
    return zipCoordinateRepository.findById(zipCode)
        .map(this::toDetail)
        .orElseThrow(() -> new ResourceNotFoundException(
            "ZIP coordinate not found: " + zipCode));
  }

  private Map<String, ZipCoordinate> existingCensusCoordinatesByZipCode() {
    var coordinates = new LinkedHashMap<String, ZipCoordinate>();
    zipCoordinateRepository.findAllBySource(ZipCoordinateGazetteerReader.CENSUS_SOURCE)
        .forEach(coordinate -> coordinates.put(coordinate.getZipCode(), coordinate));
    return coordinates;
  }

  private boolean mergeChangedValues(ZipCoordinate existing, ZipCoordinate imported) {
    if (sameImportedValues(existing, imported)) {
      return false;
    }
    existing.setLatitude(imported.getLatitude());
    existing.setLongitude(imported.getLongitude());
    existing.setSource(imported.getSource());
    existing.setSourceYear(imported.getSourceYear());
    return true;
  }

  private boolean sameImportedValues(ZipCoordinate existing, ZipCoordinate imported) {
    return Double.compare(existing.getLatitude(), imported.getLatitude()) == 0
        && Double.compare(existing.getLongitude(), imported.getLongitude()) == 0
        && existing.getSourceYear() == imported.getSourceYear()
        && imported.getSource().equals(existing.getSource());
  }

  private ZipCoordinateDetail toDetail(ZipCoordinate coordinate) {
    return ZipCoordinateDetail.builder()
        .zipCode(coordinate.getZipCode())
        .latitude(coordinate.getLatitude())
        .longitude(coordinate.getLongitude())
        .source(coordinate.getSource())
        .sourceYear(coordinate.getSourceYear())
        .build();
  }

  private String normalizeZipCode(String requestedZipCode) throws InvalidRequestException {
    var normalized = requestedZipCode == null ? "" : requestedZipCode.strip();
    if (normalized.matches("\\d{5}")) {
      return normalized;
    }
    if (normalized.matches("\\d{5}-\\d{4}")) {
      return normalized.substring(0, 5);
    }
    throw new InvalidRequestException("ZIP code must be a valid 5-digit US ZIP code.");
  }
}
