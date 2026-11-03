//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * The set of approved versions that the engines of one target load: the payload of a signed
 * {@code .release.jws} file, read with {@link ReleaseJson} and verified with
 * {@link ApprovalVerifier#verifyRelease}. An engine built with {@link RuleStone.Builder#requireRelease}
 * loads exactly the versions a release lists, and takes its canary and shadow versions from it.
 *
 * <pre>{@code
 * {
 *   "type": "rulestone.release",
 *   "target": "retail-credit-prod",
 *   "sequence": 118,
 *   "previous": "sha256:c41d…",
 *   "artifacts": [
 *     { "kind": "table", "id": "loan_eligibility", "version": "3@sha256:0a7e…", "state": "RETIRED" },
 *     { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…", "state": "ACTIVE" },
 *     { "kind": "table", "id": "loan_eligibility", "version": "5@sha256:2b9e…", "state": "ACTIVE" }
 *   ],
 *   "routing": [ { "decision": "loan_eligibility", "kind": "SHADOW", "version": "5@sha256:2b9e…" } ],
 *   "issuedAt": "2026-11-20T07:45:00Z"
 * }
 * }</pre>
 *
 * @param target    the deployed application or decision service whose engines load the release
 * @param sequence  the release's number for its target, from 1, growing with every release
 * @param previous  {@code sha256:<hex>} of the canonical form of release {@code sequence - 1};
 *                  absent exactly in the first release
 * @param artifacts the exact versions the engines load, at least one; at most one reason-code catalog
 * @param routing   the canary and shadow versions, each one of the listed {@code ACTIVE} versions
 * @param issuedAt  when the release was signed, a UTC instant such as {@code 2026-11-20T07:45:00Z}
 */
public record Release(
        String target,
        long sequence,
        Optional<String> previous,
        List<Entry> artifacts,
        List<Route> routing,
        String issuedAt) {

    /** The value of the release's {@code type} field. */
    public static final String TYPE = "rulestone.release";

    /** The largest sequence, the largest integer that both engines represent exactly (2<sup>53</sup> - 1). */
    public static final long MAX_SEQUENCE = 9_007_199_254_740_991L;

    public Release {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(previous, "previous");
        artifacts = List.copyOf(artifacts);
        routing = List.copyOf(routing);
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    /**
     * The state of a listed version. Both are loaded: a retired version stays registered so that
     * back-dated requests and replays still select it, and so that it does not extend its predecessor.
     */
    public enum State {
        /** Part of the current release, and possibly in force. */
        ACTIVE,
        /** No longer in force on any date from today on; loaded for back-dated decisions and replays. */
        RETIRED
    }

    /**
     * One listed version.
     *
     * @param kind    the kind of artifact
     * @param id      its id; for a catalog, the name the repository gives it, by default {@code reason-codes}
     * @param version its exact version, {@code <label>@sha256:<hex>}
     * @param state   whether it is {@code ACTIVE} or {@code RETIRED}
     */
    public record Entry(ArtifactKind kind, String id, String version, State state) {

        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(state, "state");
        }

        /** The version label, the part of {@link #version()} before {@code @sha256:}. */
        public String label() {
            return Release.label(version);
        }
    }

    /** How a routed version takes traffic, as {@link vn.io.dxa.rulestone.api.DecisionRecord.Routing} records it. */
    public enum RouteKind {
        /** Answers a share of the requests; see {@link RuleStone.Builder#canary}. */
        CANARY,
        /** Runs beside every request without answering it; see {@link RuleStone.Builder#shadow}. */
        SHADOW
    }

    /**
     * A canary or shadow version of a decision table or flow.
     *
     * @param decision the id of the decision
     * @param kind     {@code CANARY} or {@code SHADOW}
     * @param version  the exact version routed to, which the release lists as {@code ACTIVE}
     * @param share    for a canary, the share of correlation ids routed to it: above 0, at most 1,
     *                 with at most 6 decimals, e.g. {@code 0.05}; absent for a shadow
     */
    public record Route(String decision, RouteKind kind, String version, OptionalDouble share) {

        public Route {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(share, "share");
        }

        public static Route canary(String decision, String version, double share) {
            return new Route(decision, RouteKind.CANARY, version, OptionalDouble.of(share));
        }

        public static Route shadow(String decision, String version) {
            return new Route(decision, RouteKind.SHADOW, version, OptionalDouble.empty());
        }

        /** The version label, the part of {@link #version()} before {@code @sha256:}. */
        public String label() {
            return Release.label(version);
        }
    }

    static String label(String exactVersion) {
        int at = exactVersion.indexOf("@sha256:");
        return at < 0 ? exactVersion : exactVersion.substring(0, at);
    }

    /** Whether {@code share} is a valid canary share: above 0, at most 1, with at most 6 decimals. */
    static boolean validShare(double share) {
        return share > 0 && share <= 1 && BigDecimal.valueOf(share).stripTrailingZeros().scale() <= 6;
    }

    /** A share as the canonical form writes it: plain decimal, no trailing zeros, e.g. {@code 0.05} or {@code 1}. */
    static BigDecimal shareDecimal(double share) {
        return new BigDecimal(BigDecimal.valueOf(share).stripTrailingZeros().toPlainString());
    }
}
