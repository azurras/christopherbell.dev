package dev.christopherbell.whatsforlunch.workflow.engine.retry;

import java.time.Instant;

/**
 * Represents a retry policy for workflow operations.
 */
public interface RetryPolicy {

  /**
   * Calculates the next retry interval based on the current backoff value.
   *
   * @param backoff the current backoff value
   * @return the next retry interval in minutes
   */
  int calculateNextRetry(int backoff);

  /**
   * Returns the timeout duration for the workflow in minutes.
   *
   * @return the workflow timeout in minutes
   */
  int getWorkflowTimeOutInMinutes();

  /**
   * Returns the backoff time duration between retries in minutes.
   *
   * @return the backoff time in minutes
   */
  int getBackoffTimeInMinutes();

  /**
   * Determines if the job is still retryable based on the timeout and start time.
   *
   * @param timeOutInMinutes the timeout duration in minutes
   * @param startTime the start time of the job
   * @return true if the job is still retryable, false otherwise
   */
  boolean isJobStillRetryable(int timeOutInMinutes, Instant startTime);

}
