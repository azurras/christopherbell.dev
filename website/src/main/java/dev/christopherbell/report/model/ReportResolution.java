package dev.christopherbell.report.model;

/**
 * Resolution actions available for a report.
 */
public enum ReportResolution {
  REOPEN,
  CLOSE_NO_ACTION,
  DELETE_POST,
  DELETE_POST_AND_SUSPEND_USER
}
