//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Settings of a RuleStone job worker. Immutable; create with {@link #builder()}.
 */
public final class RuleStoneWorkerOptions {

    /** The default job type. */
    public static final String DEFAULT_JOB_TYPE = "rulestone:decision";

    private final String jobType;
    private final List<String> fetchVariables;
    private final String defaultErrorCode;
    private final Duration retryBackoff;
    private final Clock clock;
    private final Optional<String> workerName;

    private RuleStoneWorkerOptions(Builder b) {
        this.jobType = b.jobType;
        this.fetchVariables = b.fetchVariables;
        this.defaultErrorCode = b.defaultErrorCode;
        this.retryBackoff = b.retryBackoff;
        this.clock = b.clock;
        this.workerName = Optional.ofNullable(b.workerName);
    }

    /**
     * Options with every default: job type {@value #DEFAULT_JOB_TYPE}, all variables fetched, the
     * system clock in the JVM's default time zone.
     *
     * @return the default options
     */
    public static RuleStoneWorkerOptions defaults() {
        return builder().build();
    }

    /**
     * Starts building options.
     *
     * @return a builder holding the defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The job type the worker subscribes to.
     *
     * @return the job type, by default {@value #DEFAULT_JOB_TYPE}
     */
    public String jobType() {
        return jobType;
    }

    /**
     * The variables fetched with each job; empty means all variables visible to the task.
     *
     * @return the variable names
     */
    public List<String> fetchVariables() {
        return fetchVariables;
    }

    /**
     * The BPMN error code used when a task has no {@code errorCode} header.
     *
     * @return the error code, by default {@code RULESTONE_DECISION_FAILED}
     */
    public String defaultErrorCode() {
        return defaultErrorCode;
    }

    /**
     * How long Zeebe waits before retrying a job that failed unexpectedly.
     *
     * @return the backoff, by default 10 seconds
     */
    public Duration retryBackoff() {
        return retryBackoff;
    }

    /**
     * The clock that sets the business date of tasks without an {@code asOfVariable} header.
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }

    /**
     * The worker name reported to Zeebe, if set.
     *
     * @return the name, or empty for the client's default
     */
    public Optional<String> workerName() {
        return workerName;
    }

    /** Builds {@link RuleStoneWorkerOptions}. */
    public static final class Builder {

        private String jobType = DEFAULT_JOB_TYPE;
        private List<String> fetchVariables = List.of();
        private String defaultErrorCode = RuleStoneHeaders.DEFAULT_ERROR_CODE;
        private Duration retryBackoff = Duration.ofSeconds(10);
        private Clock clock = Clock.systemDefaultZone();
        private String workerName;

        private Builder() {
        }

        /**
         * Sets the job type. Use one job type per decision when you restrict fetched variables to
         * that decision's inputs.
         *
         * @param jobType the job type, e.g. {@code rulestone:loan_eligibility}
         * @return this builder
         */
        public Builder jobType(String jobType) {
            this.jobType = requireText(jobType, "jobType");
            return this;
        }

        /**
         * Restricts the variables fetched with each job, which saves bandwidth and keeps unrelated
         * personal data away from the worker. List every input of every decision the job type
         * serves, and every as-of variable its tasks name.
         *
         * @param names the variable names; none means all variables
         * @return this builder
         */
        public Builder fetchVariables(Collection<String> names) {
            this.fetchVariables = List.copyOf(names);
            return this;
        }

        /**
         * Restricts the variables fetched with each job; see {@link #fetchVariables(Collection)}.
         *
         * @param names the variable names; none means all variables
         * @return this builder
         */
        public Builder fetchVariables(String... names) {
            return fetchVariables(List.of(names));
        }

        /**
         * Sets the BPMN error code used when a task has no {@code errorCode} header.
         *
         * @param errorCode the error code
         * @return this builder
         */
        public Builder defaultErrorCode(String errorCode) {
            this.defaultErrorCode = requireText(errorCode, "errorCode");
            return this;
        }

        /**
         * Sets how long Zeebe waits before retrying a job that failed unexpectedly.
         *
         * @param backoff the backoff; zero retries immediately
         * @return this builder
         */
        public Builder retryBackoff(Duration backoff) {
            Objects.requireNonNull(backoff, "backoff");
            if (backoff.isNegative()) {
                throw new IllegalArgumentException("backoff must not be negative");
            }
            this.retryBackoff = backoff;
            return this;
        }

        /**
         * Sets the clock for tasks without an as-of variable. Use your bank's business time zone,
         * e.g. {@code Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))}.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the worker name reported to Zeebe.
         *
         * @param name the name
         * @return this builder
         */
        public Builder workerName(String name) {
            this.workerName = requireText(name, "name");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public RuleStoneWorkerOptions build() {
            return new RuleStoneWorkerOptions(this);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
