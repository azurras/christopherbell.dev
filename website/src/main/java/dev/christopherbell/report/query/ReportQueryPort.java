package dev.christopherbell.report.query;

import dev.christopherbell.libs.api.exception.InvalidRequestException;

/** Persistence-neutral report queue query boundary. */
public interface ReportQueryPort {
  ReportPage query(ReportQuery request) throws InvalidRequestException;
}
