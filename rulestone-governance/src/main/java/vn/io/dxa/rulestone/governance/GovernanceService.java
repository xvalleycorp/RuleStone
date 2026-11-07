//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalStatementJson;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.JwsWriter;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * The governance service: the lifecycle of artifact versions in change sets, maker-checker with
 * approval policies, emergency changes, the signed statements and releases that engines accept, and
 * the hash-chained log of every event.
 *
 * <pre>{@code
 * GovernanceService service = GovernanceService.builder()
 *         .store(new InMemoryGovernanceStore())
 *         .principals(resolver)                    // who is calling, from the bank's SSO
 *         .signer(PrivateKeySigner.of("gov-2026", privateKey))
 *         .policy(policy)
 *         .build();
 *
 * resolver.actAs(maker);   service.createChangeSet("CHG-1", "Lower the DTI limit");
 *                          service.saveDraft("CHG-1", ArtifactKind.TABLE, tableJson);
 *                          service.submit("CHG-1");
 * resolver.actAs(checker); service.approve("CHG-1", "RULE_CHECKER", "Matches the committee minutes");
 * resolver.actAs(releaser); service.activate("CHG-1", "retail-credit-prod");
 *
 * RuleStoneEngine engine = RuleStone.builder()
 *         .snapshot(service.repository().load("retail-credit-prod"))
 *         .requireApproved(verifier).requireRelease("retail-credit-prod").build();
 * }</pre>
 *
 * <p>Every operation takes the caller from the {@link PrincipalResolver}, checks the rules, and
 * changes the {@link GovernanceStore} in one atomic update together with the log entries it
 * causes. An operation that breaks a rule throws a {@link GovernanceException} and changes nothing.
 * Operations are serialised: one writer at a time, as the log requires. The service reads nothing
 * on the decision path; engines verify what it issues when they are built.
 *
 * <p>Messages, log entries and statements hold change set ids, versions, hashes, principals, roles
 * and the free text of comments and reasons, never rule content or input values.
 */
public final class GovernanceService {

    private static final Pattern CHANGE_SET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final GovernanceStore store;
    private final PrincipalResolver principals;
    private final Signer signer;
    private final Clock clock;
    private final Supplier<String> ids;
    private final String issuer;
    private final Consumer<LogEntry> listener;
    private final ReentrantLock lock = new ReentrantLock();

    private GovernanceService(Builder builder) {
        this.store = builder.store;
        this.principals = builder.principals;
        this.signer = builder.signer;
        this.clock = builder.clock;
        this.ids = builder.ids;
        this.issuer = builder.issuer;
        this.listener = builder.listener;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------------------------------------
    // Reading

    public GovernanceStore store() {
        return store;
    }

    /** A read-only repository over the store, for engines: see {@link StoreRuleRepository}. */
    public RuleRepository repository() {
        return new StoreRuleRepository(store);
    }

    /** The policy in force. */
    public GovernancePolicy policy() {
        return store.policy().orElseThrow();
    }

    /** @throws GovernanceException with {@link GovernanceException.Code#NOT_FOUND} if there is no such change set */
    public ChangeSet changeSet(String id) {
        return store.changeSet(id).orElseThrow(() -> new GovernanceException(GovernanceException.Code.NOT_FOUND,
                "no change set " + id));
    }

    public List<ChangeSet> changeSets() {
        return store.changeSets();
    }

    /** The lifecycle state of a version label: that of the change set item that carries it, if any change set does. */
    public Optional<LifecycleState> stateOf(ArtifactKind kind, String id, String label) {
        // Prefer the change set that holds the bound label; otherwise the newest draft of it.
        Optional<GovernanceStore.ArtifactVersion> bound = store.binding(kind, id, label);
        for (ChangeSet cs : store.changeSets()) {
            for (ChangeSet.Item item : cs.items()) {
                if (item.kind() == kind && item.id().equals(id) && item.label().equals(label)
                        && (bound.isEmpty() || bound.get().changeSet().equals(cs.id()))) {
                    return Optional.of(item.state());
                }
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // Drafting and submission

    /**
     * Creates an empty change set in {@code DRAFT}.
     *
     * @param id    the change set's id, such as {@code CHG-2026-0042}: letters, digits, {@code . _ -}
     * @param title what it is for, short free text
     */
    public ChangeSet createChangeSet(String id, String title) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            requireMaker(who, policy);
            if (id == null || !CHANGE_SET_ID.matcher(id).matches()) {
                throw invalid("a change set id is letters, digits, '.', '_' and '-', up to 64 characters");
            }
            String text = nonBlank(title, "title");
            if (store.changeSet(id).isPresent()) {
                throw new GovernanceException(GovernanceException.Code.ALREADY_EXISTS, "change set " + id + " exists");
            }
            Tx tx = new Tx();
            tx.event(EventType.DRAFT_CREATED, actor(who), id, List.of(), Map.of("title", text));
            tx.put(ChangeSet.create(id, text, who.id()));
            tx.commit();
            return changeSet(id);
        });
    }

    /**
     * Saves a revision of an item of a change set: a table, flow or parameter set, whose id and version
     * label come from the content, or the reason-code catalog, named {@value ApprovalStatement#CATALOG_ID}.
     * The saver becomes a maker of the change set. Saving into a submitted or rejected change set returns
     * it to {@code DRAFT} and voids the approvals given so far.
     *
     * @throws GovernanceException if the content is not valid, the change set is approved or active, or the
     *         version label is already bound to a content hash by an approval
     */
    public ChangeSet saveDraft(String changeSetId, ArtifactKind kind, String content) {
        return saveDraft(changeSetId, kind, null, content);
    }

    /** Like {@link #saveDraft(String, ArtifactKind, String)}, for an artifact with an expected id; a catalog is named {@code id}. */
    public ChangeSet saveDraft(String changeSetId, ArtifactKind kind, String id, String content) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            requireMaker(who, policy);
            ChangeSet cs = changeSet(changeSetId);
            if (cs.state() != LifecycleState.DRAFT && cs.state() != LifecycleState.SUBMITTED
                    && cs.state() != LifecycleState.REJECTED) {
                throw illegal("change set " + cs.id() + " is " + cs.state() + ": an approved version is never edited, "
                        + "supersede it with a new version in a new change set");
            }
            ArtifactContent parsed = ArtifactContent.parse(Objects.requireNonNull(kind, "kind"), id,
                    Objects.requireNonNull(content, "content"));
            requireUnbound(parsed.kind(), parsed.id(), parsed.label());
            ChangeSet.Edit edit = cs.edit();
            int voided = edit.approvals.size();
            if (cs.state() != LifecycleState.DRAFT) {
                edit.approvals.clear();
                edit.rejection = Optional.empty();
                edit.moveTo(LifecycleState.DRAFT);
            }
            ChangeSet.Item item = new ChangeSet.Item(parsed.kind(), parsed.id(), parsed.label(), parsed.exactVersion(),
                    content, LifecycleState.DRAFT);
            boolean replaced = false;
            for (int i = 0; i < edit.items.size(); i++) {
                if (edit.items.get(i).sameLabel(item)) {
                    edit.items.set(i, item);
                    replaced = true;
                }
            }
            if (!replaced) {
                edit.items.add(item);
            }
            edit.maker(who.id());
            who.onBehalfOf().ifPresent(edit::maker);
            Map<String, Object> details = new TreeMap<>();
            details.put("submission", (long) cs.submission());
            if (voided > 0) {
                details.put("voidedApprovals", (long) voided);
            }
            who.onBehalfOf().ifPresent(person -> details.put("onBehalfOf", person));
            Tx tx = new Tx();
            tx.event(EventType.DRAFT_SAVED, actor(who), cs.id(), List.of(parsed.subject()), details);
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /**
     * Freezes a draft for review at the exact content hash of each of its items. The submitter becomes a
     * maker too, so that someone who wrote, or submitted, a change can never approve it.
     */
    public ChangeSet submit(String changeSetId) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            requireMaker(who, policy);
            ChangeSet cs = changeSet(changeSetId);
            requireState(cs, LifecycleState.DRAFT, "submit");
            if (cs.items().isEmpty()) {
                throw illegal("change set " + cs.id() + " has no items");
            }
            for (ChangeSet.Item item : cs.items()) {
                requireUnbound(item.kind(), item.id(), item.label());
            }
            List<GovernancePolicy.Tier> tiers = tiersOf(cs, policy);
            GovernancePolicy.Tier strictest = GovernancePolicy.strictest(tiers);
            ChangeSet.Edit edit = cs.edit();
            edit.maker(who.id());
            edit.submission = cs.submission() + 1;
            edit.moveTo(LifecycleState.SUBMITTED);
            Map<String, Object> details = new TreeMap<>();
            details.put("submission", (long) edit.submission);
            details.put("tier", strictest.name());
            details.put("tiers", tiers.stream().map(GovernancePolicy.Tier::name).toList());
            details.put("requiredApprovals", (long) strictest.approvals());
            details.put("emergency", cs.emergency().isPresent());
            Tx tx = new Tx();
            tx.event(EventType.SUBMITTED, actor(who), cs.id(), cs.subjects(), details);
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /** A maker takes a submission back to {@code DRAFT} before a decision; the approvals given so far are void. */
    public ChangeSet withdraw(String changeSetId, String reason) {
        return locked(() -> {
            Principal who = caller();
            ChangeSet cs = changeSet(changeSetId);
            requireState(cs, LifecycleState.SUBMITTED, "withdraw");
            if (!cs.makers().contains(who.id())) {
                throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                        who.id() + " is not a maker of change set " + cs.id() + " and cannot withdraw it");
            }
            ChangeSet.Edit edit = cs.edit();
            int voided = edit.approvals.size();
            edit.approvals.clear();
            edit.moveTo(LifecycleState.DRAFT);
            Tx tx = new Tx();
            tx.event(EventType.WITHDRAWN, actor(who), cs.id(), cs.subjects(),
                    Map.of("reason", nonBlank(reason, "reason"), "voidedApprovals", (long) voided));
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /**
     * Comments on a change set, optionally at a location in an item written as the JSON path the
     * validator uses, such as {@code loan_eligibility@4 $.rules[2].when}. Comments are free text that the
     * log keeps for good: do not paste customer data into them.
     *
     * @param location where, or null
     */
    public void comment(String changeSetId, String location, String text) {
        locked(() -> {
            Principal who = caller();
            ChangeSet cs = changeSet(changeSetId);
            Map<String, Object> details = new TreeMap<>();
            details.put("text", nonBlank(text, "text"));
            if (location != null && !location.isBlank()) {
                details.put("location", location);
            }
            Tx tx = new Tx();
            tx.event(EventType.COMMENTED, actor(who), cs.id(), cs.subjects(), details);
            tx.commit();
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Maker-checker

    /**
     * Approves a submitted change set in a role. A checker is not a maker of the change set, holds the role
     * they approve in, and that role is one that the tiers of the change set's items require; an AI
     * principal and a governance administrator cannot approve. When the policy of every tier is met, the
     * service signs the approval statement, binds each version label to its content hash and the change set
     * becomes {@code APPROVED}.
     *
     * <p>On an emergency change set, an approval in an emergency role is enough: see
     * {@link #declareEmergency}.
     *
     * @param role    the role approved in, one the caller holds
     * @param comment what the checker wrote, or null
     */
    public ChangeSet approve(String changeSetId, String role, String comment) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            ChangeSet cs = changeSet(changeSetId);
            requireState(cs, LifecycleState.SUBMITTED, "approve");
            requireChecker(who, cs, policy);
            String approvedRole = nonBlank(role, "role");
            if (cs.approvals().stream().anyMatch(a -> a.principal().equals(who.id()))) {
                throw new GovernanceException(GovernanceException.Code.DUPLICATE_APPROVAL,
                        who.id() + " has already approved change set " + cs.id());
            }
            requireRole(who, approvedRole);
            List<GovernancePolicy.Tier> tiers = tiersOf(cs, policy);
            boolean emergencyRole = cs.emergency().isPresent() && policy.emergencyRoles().contains(approvedRole);
            Set<String> allowed = new TreeSet<>(tierRoles(tiers));
            if (cs.emergency().isPresent()) {
                allowed.addAll(policy.emergencyRoles());
            }
            if (!allowed.contains(approvedRole)) {
                throw new GovernanceException(GovernanceException.Code.ROLE_NOT_REQUIRED,
                        "role " + approvedRole + " is not required by change set " + cs.id() + ": it needs one of " + allowed);
            }
            if (!emergencyRole) {
                requireDistinctUnit(who, tiers, cs.approvals());
            }
            for (ChangeSet.Item item : cs.items()) {
                requireUnbound(item.kind(), item.id(), item.label());
            }

            Instant now = clock.instant();
            ChangeSet.Approval approval = new ChangeSet.Approval(who.id(), approvedRole, who.unit(), now,
                    optionalText(comment), emergencyRole);
            ChangeSet.Edit edit = cs.edit();
            edit.approvals.add(approval);
            Map<String, Object> details = new TreeMap<>();
            details.put("role", approvedRole);
            optionalText(comment).ifPresent(c -> details.put("comment", c));
            Tx tx = new Tx();
            tx.event(emergencyRole ? EventType.EMERGENCY_APPROVED : EventType.APPROVED, actor(who), cs.id(),
                    cs.subjects(), details);

            List<ChangeSet.Approval> regular = edit.approvals.stream().filter(a -> !a.emergency()).toList();
            boolean fullPolicy = GovernancePolicy.unmet(tiers, regular).isEmpty();
            boolean emergencyApproved = edit.approvals.stream().anyMatch(ChangeSet.Approval::emergency);
            if (fullPolicy || emergencyApproved) {
                boolean emergency = !fullPolicy;
                String tier = emergency ? "emergency" : GovernancePolicy.strictest(tiers).name();
                Optional<Instant> due = emergency ? Optional.of(policy.postApprovalDue(now)) : Optional.empty();
                edit.moveTo(LifecycleState.APPROVED);
                tx.issue(cs, edit, EventType.APPROVAL_ISSUED, policy.ref(tier), emergency, due,
                        emergency ? edit.approvals : regular, cs.id() + RuleStone.APPROVAL_FILE_SUFFIX);
                for (ChangeSet.Item item : edit.items) {
                    tx.update.version(new GovernanceStore.ArtifactVersion(item.kind(), item.id(), item.label(),
                            item.exactVersion(), item.content(), cs.id(), LOG_TIME.format(now)));
                }
                if (emergency) {
                    edit.emergency = Optional.of(new ChangeSet.Emergency(cs.emergency().get().reason(),
                            cs.emergency().get().declaredBy(), ChangeSet.EmergencyStatus.AWAITING_POST_APPROVAL, due, false));
                } else if (cs.emergency().isPresent()) {
                    edit.emergency = Optional.of(new ChangeSet.Emergency(cs.emergency().get().reason(),
                            cs.emergency().get().declaredBy(), ChangeSet.EmergencyStatus.POST_APPROVED, Optional.empty(), false));
                }
            }
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /**
     * Refuses a submission with a reason, which is logged. A rejection ends the submission and voids its
     * approvals; the author revises the draft and submits again. The same checks apply as for an approval:
     * the caller holds a role that checks this change set and is not a maker.
     */
    public ChangeSet reject(String changeSetId, String reason) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            ChangeSet cs = changeSet(changeSetId);
            requireState(cs, LifecycleState.SUBMITTED, "reject");
            requireChecker(who, cs, policy);
            requireChecksThis(who, cs, policy);
            String text = nonBlank(reason, "reason");
            ChangeSet.Edit edit = cs.edit();
            int voided = edit.approvals.size();
            edit.approvals.clear();
            edit.rejection = Optional.of(text);
            edit.moveTo(LifecycleState.REJECTED);
            Tx tx = new Tx();
            tx.event(EventType.REJECTED, actor(who), cs.id(), cs.subjects(),
                    Map.of("reason", text, "voidedApprovals", (long) voided));
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Emergency changes

    /**
     * Declares a change set an emergency, for a rule that must change at once. It still needs one approver
     * who is not a maker and holds an emergency role: four-eyes is reduced, never removed. Waiting steps
     * that the tier requires are deferred, and the full policy must post-approve the same content hashes
     * by the due instant (see {@link #postApprove}). Declaring needs the same right as making a change.
     *
     * @throws GovernanceException if the policy names no emergency role, or the change set is already past review
     */
    public ChangeSet declareEmergency(String changeSetId, String reason) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            requireMaker(who, policy);
            ChangeSet cs = changeSet(changeSetId);
            if (policy.emergencyRoles().isEmpty()) {
                throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                        "the policy names no emergency role: emergency changes are not enabled");
            }
            if (cs.state() != LifecycleState.DRAFT && cs.state() != LifecycleState.SUBMITTED) {
                throw illegal("change set " + cs.id() + " is " + cs.state() + ": an emergency is declared before approval");
            }
            if (cs.emergency().isPresent()) {
                throw illegal("change set " + cs.id() + " is already an emergency");
            }
            String text = nonBlank(reason, "reason");
            ChangeSet.Edit edit = cs.edit();
            edit.emergency = Optional.of(new ChangeSet.Emergency(text, who.id(), ChangeSet.EmergencyStatus.DECLARED,
                    Optional.empty(), false));
            Tx tx = new Tx();
            tx.event(EventType.EMERGENCY_DECLARED, actor(who), cs.id(), cs.subjects(), Map.of("reason", text));
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /**
     * Post-approves an emergency change with the full policy: the same checks as {@link #approve}, in a role
     * of the tiers, not an emergency role. When the policy is met, a second statement without the emergency
     * flag is issued for the same content hashes and the log shows {@code POST_APPROVED}.
     */
    public ChangeSet postApprove(String changeSetId, String role, String comment) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            ChangeSet cs = changeSet(changeSetId);
            requireAwaitingPostApproval(cs);
            requireChecker(who, cs, policy);
            String approvedRole = nonBlank(role, "role");
            if (cs.postApprovals().stream().anyMatch(a -> a.principal().equals(who.id()))) {
                throw new GovernanceException(GovernanceException.Code.DUPLICATE_APPROVAL,
                        who.id() + " has already post-approved change set " + cs.id());
            }
            requireRole(who, approvedRole);
            List<GovernancePolicy.Tier> tiers = tiersOf(cs, policy);
            if (!tierRoles(tiers).contains(approvedRole)) {
                throw new GovernanceException(GovernanceException.Code.ROLE_NOT_REQUIRED,
                        "role " + approvedRole + " is not required by the full policy of change set " + cs.id()
                                + ": it needs one of " + new TreeSet<>(tierRoles(tiers)));
            }
            requireDistinctUnit(who, tiers, cs.postApprovals());

            Instant now = clock.instant();
            ChangeSet.Approval approval = new ChangeSet.Approval(who.id(), approvedRole, who.unit(), now,
                    optionalText(comment), false);
            ChangeSet.Edit edit = cs.edit();
            edit.postApprovals.add(approval);
            Map<String, Object> details = new TreeMap<>();
            details.put("role", approvedRole);
            details.put("post", true);
            optionalText(comment).ifPresent(c -> details.put("comment", c));
            Tx tx = new Tx();
            tx.event(EventType.APPROVED, actor(who), cs.id(), cs.subjects(), details);
            if (GovernancePolicy.unmet(tiers, edit.postApprovals).isEmpty()) {
                tx.issue(cs, edit, EventType.POST_APPROVED, policy.ref(GovernancePolicy.strictest(tiers).name()), false,
                        Optional.empty(), edit.postApprovals, cs.id() + ".post" + RuleStone.APPROVAL_FILE_SUFFIX);
                ChangeSet.Emergency emergency = cs.emergency().get();
                edit.emergency = Optional.of(new ChangeSet.Emergency(emergency.reason(), emergency.declaredBy(),
                        ChangeSet.EmergencyStatus.POST_APPROVED, emergency.postApprovalDue(), emergency.overdueAlerted()));
            }
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /**
     * Refuses an emergency change after the fact. The version stays loaded where it is, because a running
     * engine would keep it anyway; it must be superseded by a new version, and the decisions made under it
     * found by their records' {@code version}. A rejected change set cannot be activated into more targets.
     */
    public ChangeSet rejectPostApproval(String changeSetId, String reason) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            ChangeSet cs = changeSet(changeSetId);
            requireAwaitingPostApproval(cs);
            requireChecker(who, cs, policy);
            if (tiersOf(cs, policy).stream().noneMatch(t -> t.roles().stream().anyMatch(who::hasRole))) {
                throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                        who.id() + " holds no role of the full policy of change set " + cs.id());
            }
            String text = nonBlank(reason, "reason");
            ChangeSet.Emergency emergency = cs.emergency().get();
            ChangeSet.Edit edit = cs.edit();
            edit.emergency = Optional.of(new ChangeSet.Emergency(emergency.reason(), emergency.declaredBy(),
                    ChangeSet.EmergencyStatus.POST_APPROVAL_REJECTED, emergency.postApprovalDue(), emergency.overdueAlerted()));
            Tx tx = new Tx();
            tx.event(EventType.POST_APPROVAL_REJECTED, actor(who), cs.id(), cs.subjects(), Map.of("reason", text));
            tx.put(edit.build());
            tx.commit();
            return changeSet(cs.id());
        });
    }

    /**
     * Raises an alert for every emergency change whose post-approval is overdue, once each: a
     * {@code POST_APPROVAL_OVERDUE} entry in the log, passed to the listener. Nothing else happens: engines
     * do not refuse to build because of it. Call it on a timer.
     *
     * @return the ids of the change sets alerted now
     */
    public List<String> checkOverdue() {
        return locked(() -> {
            Instant now = clock.instant();
            List<String> alerted = new ArrayList<>();
            Tx tx = new Tx();
            for (ChangeSet cs : store.changeSets()) {
                Optional<ChangeSet.Emergency> emergency = cs.emergency();
                if (emergency.isPresent() && emergency.get().status() == ChangeSet.EmergencyStatus.AWAITING_POST_APPROVAL
                        && !emergency.get().overdueAlerted() && emergency.get().postApprovalDue().isPresent()
                        && now.isAfter(emergency.get().postApprovalDue().get())) {
                    Instant due = emergency.get().postApprovalDue().get();
                    tx.event(EventType.POST_APPROVAL_OVERDUE, serviceActor(), cs.id(), cs.subjects(),
                            Map.of("due", due.toString(), "overdueMinutes", ChronoUnit.MINUTES.between(due, now)));
                    ChangeSet.Edit edit = cs.edit();
                    edit.emergency = Optional.of(new ChangeSet.Emergency(emergency.get().reason(),
                            emergency.get().declaredBy(), emergency.get().status(), emergency.get().postApprovalDue(), true));
                    tx.put(edit.build());
                    alerted.add(cs.id());
                }
            }
            if (!alerted.isEmpty()) {
                tx.commit();
            }
            return alerted;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Releases

    /**
     * Activates an approved change set in the release of a target: the service issues the next signed
     * release, which lists every version of the previous release and the items of this change set as
     * {@code ACTIVE}, chained to the previous release by its hash. Versions that can no longer be in force
     * become {@code RETIRED}, still listed so that replays work. Canary and shadow routing of the previous
     * release carries over while the routed version is active.
     *
     * <p>If the policy names release roles, the caller holds one; otherwise any principal that is not an AI
     * may activate, because the content is approved and fixed.
     *
     * @return the signed release
     */
    public GovernanceStore.IssuedRelease activate(String changeSetId, String target) {
        return activate(changeSetId, target, null);
    }

    /**
     * Like {@link #activate(String, String)}, with the canary and shadow routing of the new release, which
     * replaces that of the previous one. Routing is part of the signed release, so engines route only as the
     * release says; setting it needs one of the policy's release roles.
     *
     * @param routing the routes, each to a table or flow version the release lists as active; empty for none
     */
    public GovernanceStore.IssuedRelease activate(String changeSetId, String target, List<Release.Route> routing) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy policy = currentPolicy();
            requireReleaser(who, policy);
            requireTarget(target);
            if (routing != null && policy.releaseRoles().isEmpty()) {
                throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                        "routing needs a release role, and the policy names none");
            }
            ChangeSet cs = changeSet(changeSetId);
            if (cs.state() != LifecycleState.APPROVED && cs.state() != LifecycleState.ACTIVE) {
                throw illegal("change set " + cs.id() + " is " + cs.state() + ": only an approved change set is activated");
            }
            if (cs.activations().stream().anyMatch(a -> a.target().equals(target))) {
                throw illegal("change set " + cs.id() + " is already active in target '" + target + "'");
            }
            if (cs.items().stream().anyMatch(i -> i.state() == LifecycleState.RETIRED)) {
                throw illegal("change set " + cs.id() + " has retired versions: a retired version is not activated again");
            }
            if (cs.emergency().isPresent()
                    && cs.emergency().get().status() == ChangeSet.EmergencyStatus.POST_APPROVAL_REJECTED) {
                throw illegal("change set " + cs.id() + " was refused in post-approval: supersede it with a new version");
            }
            return release(who, target, cs, routing);
        });
    }

    /**
     * Retires the versions of a target that can no longer be in force on today's date, according to the
     * clock: a successor that is active has taken effect, or the version's {@code effectiveTo} has passed.
     * It issues a new release only if something changed. Retirement is bookkeeping: it needs no approval.
     *
     * @return the new release, or empty if nothing was retired
     */
    public Optional<GovernanceStore.IssuedRelease> retireSuperseded(String target) {
        return locked(() -> {
            Principal who = caller();
            requireReleaser(who, currentPolicy());
            requireTarget(target);
            if (store.latestRelease(target).isEmpty()) {
                throw new GovernanceException(GovernanceException.Code.NOT_FOUND, "no release of target '" + target + "'");
            }
            Plan plan = plan(target, List.of(), null);
            if (plan.retired().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(release(who, target, null, null));
        });
    }

    private GovernanceStore.IssuedRelease release(Principal who, String target, ChangeSet activating,
            List<Release.Route> routing) {
        Plan plan = plan(target, activating == null ? List.of() : activating.items(), routing);
        Optional<GovernanceStore.IssuedRelease> latest = store.latestRelease(target);
        GovernanceStore.IssuedRelease issued = new GovernanceStore.IssuedRelease(plan.release(),
                sign(ApprovalVerifier.RELEASE_TYPE, ReleaseJson.write(plan.release())), ReleaseJson.hash(plan.release()));
        Tx tx = new Tx();
        LogEntry.Actor actor = actor(who);
        Map<String, Object> released = new TreeMap<>();
        released.put("target", target);
        released.put("sequence", plan.release().sequence());
        released.put("releaseHash", issued.hash());
        released.put("previous", latest.map(GovernanceStore.IssuedRelease::hash).orElse(""));
        if (activating != null) {
            released.put("changeSet", activating.id());
        }
        tx.event(EventType.RELEASED, actor, activating == null ? null : activating.id(),
                plan.added().stream().map(e -> new ApprovalStatement.Subject(e.kind(), e.id(), e.version())).toList(),
                released);
        if (activating != null) {
            tx.event(EventType.ACTIVATED, actor, activating.id(), activating.subjects(),
                    Map.of("target", target, "sequence", plan.release().sequence()));
        }
        Map<String, Release.Entry> gone = new LinkedHashMap<>();
        plan.retired().forEach(e -> gone.put(key(e), e));
        plan.dropped().forEach(e -> gone.put(key(e), e));
        Map<String, ChangeSet> affected = new LinkedHashMap<>();
        for (Release.Entry entry : gone.values()) {
            GovernanceStore.ArtifactVersion version = store.version(entry.kind(), entry.id(), entry.version()).orElseThrow();
            Map<String, Object> details = new TreeMap<>();
            details.put("target", target);
            details.put("reason", plan.dropped().contains(entry) ? "replaced" : plan.reasons().get(key(entry)));
            tx.event(EventType.RETIRED, actor, version.changeSet(),
                    List.of(new ApprovalStatement.Subject(entry.kind(), entry.id(), entry.version())), details);
            affected.putIfAbsent(version.changeSet(), store.changeSet(version.changeSet()).orElseThrow());
        }
        if (activating != null) {
            affected.put(activating.id(), activating);
        }
        for (ChangeSet cs : affected.values()) {
            ChangeSet.Edit edit = cs.edit();
            for (int i = 0; i < edit.items.size(); i++) {
                ChangeSet.Item item = edit.items.get(i);
                edit.items.set(i, item.with(settledState(item, target, plan, gone)));
            }
            if (activating != null && cs.id().equals(activating.id())) {
                edit.activations.add(new ChangeSet.Activation(target, plan.release().sequence()));
                if (edit.state == LifecycleState.APPROVED) {
                    edit.state.requireMoveTo(LifecycleState.ACTIVE);
                    edit.state = LifecycleState.ACTIVE;
                }
            }
            if (edit.state == LifecycleState.ACTIVE && edit.items.stream().allMatch(i -> i.state() == LifecycleState.RETIRED)) {
                edit.state.requireMoveTo(LifecycleState.RETIRED);
                edit.state = LifecycleState.RETIRED;
            }
            tx.put(edit.build());
        }
        tx.update.release(issued);
        tx.commit();
        return issued;
    }

    /** The state of an item after the new release of {@code target}: active if any target lists it as active. */
    private LifecycleState settledState(ChangeSet.Item item, String target, Plan plan, Map<String, Release.Entry> gone) {
        boolean listed = false;
        for (String other : allTargets(target)) {
            Release release = other.equals(target) ? plan.release() : store.latestRelease(other).orElseThrow().release();
            for (Release.Entry entry : release.artifacts()) {
                if (entry.kind() == item.kind() && entry.id().equals(item.id()) && entry.version().equals(item.exactVersion())) {
                    if (entry.state() == Release.State.ACTIVE) {
                        return LifecycleState.ACTIVE;
                    }
                    listed = true;
                }
            }
        }
        if (listed || gone.containsKey(key(item.kind(), item.id(), item.exactVersion()))) {
            return LifecycleState.RETIRED;
        }
        return item.state();
    }

    private Set<String> allTargets(String target) {
        Set<String> targets = new TreeSet<>(store.targets());
        targets.add(target);
        return targets;
    }

    /** What the next release of a target lists, and what changed against the latest one. */
    private record Plan(Release release, List<Release.Entry> added, List<Release.Entry> retired,
            List<Release.Entry> dropped, Map<String, String> reasons) {}

    private Plan plan(String target, List<ChangeSet.Item> adding, List<Release.Route> explicitRouting) {
        Optional<GovernanceStore.IssuedRelease> latest = store.latestRelease(target);
        Map<String, Release.Entry> entries = new LinkedHashMap<>();
        latest.ifPresent(l -> l.release().artifacts().forEach(e -> entries.put(key(e), e)));
        List<Release.Entry> added = new ArrayList<>();
        List<Release.Entry> dropped = new ArrayList<>();
        for (ChangeSet.Item item : adding) {
            Release.Entry entry = new Release.Entry(item.kind(), item.id(), item.exactVersion(), Release.State.ACTIVE);
            if (entries.containsKey(key(entry))) {
                continue;
            }
            if (item.kind() == ArtifactKind.CATALOG) {
                // An engine has one catalog: the new one replaces the old, which is no longer listed.
                entries.values().removeIf(old -> old.kind() == ArtifactKind.CATALOG && dropped.add(old));
            }
            entries.put(key(entry), entry);
            added.add(entry);
        }

        LocalDate today = LocalDate.now(clock);
        List<Release.Entry> retired = new ArrayList<>();
        Map<String, String> reasons = new HashMap<>();
        Map<String, ArtifactContent> contents = new HashMap<>();
        List<Release.Entry> active = entries.values().stream()
                .filter(e -> e.state() == Release.State.ACTIVE && e.kind() != ArtifactKind.CATALOG).toList();
        for (Release.Entry entry : active) {
            contents.put(key(entry), contentOf(entry));
        }
        for (Release.Entry entry : active) {
            ArtifactContent content = contents.get(key(entry));
            String reason = null;
            if (content.effectiveTo().isPresent() && !today.isBefore(content.effectiveTo().get())) {
                reason = "expired";
            } else {
                for (Release.Entry other : active) {
                    ArtifactContent successor = contents.get(key(other));
                    if (other != entry && other.kind() == entry.kind() && other.id().equals(entry.id())
                            && successor.effectiveFrom().isPresent() && !successor.effectiveFrom().get().isAfter(today)
                            && (content.effectiveFrom().isEmpty()
                                    || successor.effectiveFrom().get().isAfter(content.effectiveFrom().get()))) {
                        reason = "superseded";
                        break;
                    }
                }
            }
            if (reason != null) {
                Release.Entry gone = new Release.Entry(entry.kind(), entry.id(), entry.version(), Release.State.RETIRED);
                entries.put(key(entry), gone);
                retired.add(entry);
                reasons.put(key(entry), reason);
            }
        }

        List<Release.Entry> sorted = entries.values().stream()
                .sorted(Comparator.comparing((Release.Entry e) -> e.kind().jsonName()).thenComparing(Release.Entry::id)
                        .thenComparing(Release.Entry::version))
                .toList();
        List<Release.Route> routing = explicitRouting != null ? explicitRouting
                : latest.map(l -> l.release().routing().stream()
                        .filter(r -> sorted.stream().anyMatch(e -> e.state() == Release.State.ACTIVE
                                && (e.kind() == ArtifactKind.TABLE || e.kind() == ArtifactKind.FLOW)
                                && e.id().equals(r.decision()) && e.version().equals(r.version())))
                        .toList()).orElse(List.of());
        Release release = new Release(target, latest.map(l -> l.release().sequence() + 1).orElse(1L),
                latest.map(l -> l.hash()), sorted, routing, secondsText(clock.instant()));
        try {
            ReleaseJson.read(ReleaseJson.write(release));
        } catch (InvalidDecisionException e) {
            throw invalid("the release would not be valid: " + String.join("; ", e.problems()));
        }
        return new Plan(release, added, retired, dropped, reasons);
    }

    private ArtifactContent contentOf(Release.Entry entry) {
        GovernanceStore.ArtifactVersion version = store.version(entry.kind(), entry.id(), entry.version())
                .orElseThrow(() -> new GovernanceException(GovernanceException.Code.NOT_FOUND,
                        "the store holds no content of " + entry.id() + "@" + entry.version()));
        return ArtifactContent.parse(entry.kind(), entry.id(), version.content());
    }

    // ---------------------------------------------------------------------------------------------
    // Policy

    /**
     * Changes the governance policy; the new policy gets the next version. Policy changes are logged as
     * {@code POLICY_CHANGED} and need a governance administrator, who cannot approve rule changes. Change
     * sets already submitted are judged by the policy in force when they are decided.
     */
    public GovernancePolicy changePolicy(GovernancePolicy next) {
        return locked(() -> {
            Principal who = caller();
            GovernancePolicy current = currentPolicy();
            if (who.isAi() || who.roles().stream().noneMatch(current.administratorRoles()::contains)) {
                throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                        who.id() + " is not a governance administrator and cannot change the policy");
            }
            GovernancePolicy stored = Objects.requireNonNull(next, "next").withVersion(current.version() + 1);
            Tx tx = new Tx();
            tx.event(EventType.POLICY_CHANGED, actor(who), null, List.of(), policyDetails(current.version(), stored));
            tx.update.policy(stored);
            tx.commit();
            return stored;
        });
    }

    private static Map<String, Object> policyDetails(int from, GovernancePolicy policy) {
        Map<String, Object> details = new TreeMap<>();
        details.put("fromVersion", (long) from);
        details.put("toVersion", (long) policy.version());
        details.put("policyHash", policy.hash());
        details.put("tiers", new ArrayList<>(new TreeSet<>(policy.tiers().keySet())));
        return details;
    }

    // ---------------------------------------------------------------------------------------------
    // Checks

    private Principal caller() {
        return principals.current();
    }

    private GovernancePolicy currentPolicy() {
        return store.policy().orElseThrow();
    }

    private LogEntry.Actor actor(Principal who) {
        return new LogEntry.Actor(who.id(), principals.via());
    }

    private LogEntry.Actor serviceActor() {
        return new LogEntry.Actor("svc:" + issuer, Optional.empty());
    }

    private static void requireMaker(Principal who, GovernancePolicy policy) {
        if (!policy.makerRoles().isEmpty() && who.roles().stream().noneMatch(policy.makerRoles()::contains)) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                    who.id() + " holds none of the maker roles " + new TreeSet<>(policy.makerRoles()));
        }
    }

    private static void requireReleaser(Principal who, GovernancePolicy policy) {
        if (who.isAi()) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN, "an AI principal cannot release");
        }
        if (!policy.releaseRoles().isEmpty() && who.roles().stream().noneMatch(policy.releaseRoles()::contains)) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                    who.id() + " holds none of the release roles " + new TreeSet<>(policy.releaseRoles()));
        }
    }

    /** The checks every checker passes, in any role: not an AI, not an administrator, not a maker. */
    private static void requireChecker(Principal who, ChangeSet cs, GovernancePolicy policy) {
        if (who.isAi()) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                    "AI principal " + who.id() + " can make changes, never check them");
        }
        if (who.roles().stream().anyMatch(policy.administratorRoles()::contains)) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                    who.id() + " is a governance administrator and cannot check rule changes");
        }
        if (cs.makers().contains(who.id())) {
            throw new GovernanceException(GovernanceException.Code.FOUR_EYES,
                    who.id() + " is a maker of change set " + cs.id() + " and cannot check it");
        }
    }

    private void requireChecksThis(Principal who, ChangeSet cs, GovernancePolicy policy) {
        Set<String> roles = new TreeSet<>(tierRoles(tiersOf(cs, policy)));
        if (cs.emergency().isPresent()) {
            roles.addAll(policy.emergencyRoles());
        }
        if (roles.stream().noneMatch(who::hasRole)) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN,
                    who.id() + " holds none of the roles that check change set " + cs.id() + ": " + roles);
        }
    }

    private static void requireRole(Principal who, String role) {
        if (!who.hasRole(role)) {
            throw new GovernanceException(GovernanceException.Code.FORBIDDEN, who.id() + " does not hold role " + role);
        }
    }

    private static void requireDistinctUnit(Principal who, List<GovernancePolicy.Tier> tiers, List<ChangeSet.Approval> given) {
        if (tiers.stream().noneMatch(GovernancePolicy.Tier::distinctUnits)) {
            return;
        }
        if (who.unit().isEmpty()) {
            throw new GovernanceException(GovernanceException.Code.UNIT_NOT_DISTINCT,
                    "the policy needs approvers from different units, and " + who.id() + " has no unit");
        }
        if (given.stream().anyMatch(a -> !a.emergency() && a.unit().equals(who.unit()))) {
            throw new GovernanceException(GovernanceException.Code.UNIT_NOT_DISTINCT,
                    "the policy needs approvers from different units, and unit " + who.unit().get()
                            + " has approved already");
        }
    }

    private void requireUnbound(ArtifactKind kind, String id, String label) {
        store.binding(kind, id, label).ifPresent(bound -> {
            throw new GovernanceException(GovernanceException.Code.LABEL_BOUND,
                    kind.jsonName() + " " + id + "@" + label + " is bound to " + bound.exactVersion()
                            + " by the approval of change set " + bound.changeSet() + ": publish a new version label");
        });
    }

    private static void requireState(ChangeSet cs, LifecycleState expected, String operation) {
        if (cs.state() != expected) {
            throw illegal("cannot " + operation + " change set " + cs.id() + ": it is " + cs.state()
                    + ", and must be " + expected);
        }
    }

    private static void requireAwaitingPostApproval(ChangeSet cs) {
        if (cs.emergency().isEmpty()
                || cs.emergency().get().status() != ChangeSet.EmergencyStatus.AWAITING_POST_APPROVAL) {
            throw illegal("change set " + cs.id() + " is not an emergency change awaiting post-approval");
        }
    }

    private static void requireTarget(String target) {
        if (target == null || !TARGET.matcher(target).matches()) {
            throw invalid("a target is letters, digits, '.', '_' and '-', not starting with '.'");
        }
    }

    private static List<GovernancePolicy.Tier> tiersOf(ChangeSet cs, GovernancePolicy policy) {
        Map<String, GovernancePolicy.Tier> tiers = new TreeMap<>();
        for (ChangeSet.Item item : cs.items()) {
            GovernancePolicy.Tier tier = policy.tierFor(item.kind(), item.id());
            tiers.put(tier.name(), tier);
        }
        return List.copyOf(tiers.values());
    }

    private static Set<String> tierRoles(List<GovernancePolicy.Tier> tiers) {
        Set<String> roles = new LinkedHashSet<>();
        tiers.forEach(t -> roles.addAll(t.roles()));
        return roles;
    }

    private static String nonBlank(String text, String what) {
        if (text == null || text.isBlank()) {
            throw invalid(what + " must not be empty");
        }
        return text.strip();
    }

    private static Optional<String> optionalText(String text) {
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.strip());
    }

    private static GovernanceException illegal(String message) {
        return new GovernanceException(GovernanceException.Code.ILLEGAL_STATE, message);
    }

    private static GovernanceException invalid(String message) {
        return new GovernanceException(GovernanceException.Code.INVALID_REQUEST, message);
    }

    private static String key(Release.Entry entry) {
        return key(entry.kind(), entry.id(), entry.version());
    }

    private static String key(ArtifactKind kind, String id, String exactVersion) {
        return kind.jsonName() + " " + id + "@" + exactVersion;
    }

    private static String secondsText(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Signs {@code payload} as a JWS compact serialization with the given {@code typ}. */
    private String sign(String type, String payload) {
        String input = JwsWriter.signingInput(JwsWriter.header(signer.algorithm(), signer.keyId(), type), payload);
        byte[] signature;
        try {
            signature = signer.sign(input.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            throw new GovernanceException(GovernanceException.Code.SIGNING_FAILED,
                    "key '" + signer.keyId() + "' could not sign", e);
        }
        if (signature == null || signature.length != 64) {
            throw new GovernanceException(GovernanceException.Code.SIGNING_FAILED,
                    "key '" + signer.keyId() + "' returned a signature that is not 64 bytes");
        }
        return JwsWriter.compact(input, signature);
    }

    private <T> T locked(Supplier<T> operation) {
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // One atomic update

    /** Collects the log entries and record changes of one operation and commits them together. */
    private final class Tx {

        final GovernanceStore.Update.Builder update;
        private final List<LogEntry> entries = new ArrayList<>();
        private final Map<String, ChangeSet> changeSets = new LinkedHashMap<>();
        private String head;
        private long seq;

        Tx() {
            Optional<LogEntry> last = store.head();
            this.head = last.map(LogEntry::hash).orElse(LogEntry.GENESIS);
            this.seq = last.map(LogEntry::seq).orElse(0L);
            this.update = GovernanceStore.Update.builder(head);
        }

        LogEntry event(EventType type, LogEntry.Actor actor, String changeSet, List<ApprovalStatement.Subject> subjects,
                Map<String, Object> details) {
            LogEntry entry = LogEntry.create(++seq, ids.get(), LOG_TIME.format(clock.instant()), type, actor,
                    Optional.ofNullable(changeSet), subjects, details, head);
            head = entry.hash();
            entries.add(entry);
            update.entry(entry);
            return entry;
        }

        void put(ChangeSet changeSet) {
            changeSets.put(changeSet.id(), changeSet);
        }

        /**
         * Logs the issue of a statement for the change set as it is after {@code edit}, signs it, and adds it
         * to the update. The statement names the log entry's hash as its {@code logEntry}.
         */
        void issue(ChangeSet cs, ChangeSet.Edit edit, EventType type, String policyRef, boolean emergency,
                Optional<Instant> due, List<ChangeSet.Approval> approvers, String name) {
            String approvalId = ids.get();
            Map<String, Object> details = new TreeMap<>();
            details.put("approvalId", approvalId);
            details.put("policy", policyRef);
            details.put("emergency", emergency);
            details.put("approvers", approvers.stream().map(ChangeSet.Approval::principal).toList());
            due.ifPresent(d -> details.put("postApprovalDue", secondsText(d)));
            LogEntry entry = event(type, serviceActor(), cs.id(), cs.subjects(), details);
            ApprovalStatement statement = new ApprovalStatement(approvalId, cs.id(), cs.subjects(), edit.makers,
                    approvers.stream().map(a -> new ApprovalStatement.Approver(a.principal(), Optional.of(a.role()),
                            secondsText(a.at()))).toList(),
                    Optional.of(policyRef), emergency, due.map(GovernanceService::secondsText), issuer,
                    secondsText(clock.instant()), Optional.of(entry.hash()));
            String jws = sign(ApprovalVerifier.TYPE, ApprovalStatementJson.write(statement));
            update.approval(new GovernanceStore.IssuedApproval(name, statement, jws));
        }

        void commit() {
            changeSets.values().forEach(c -> {
                long stored = store.changeSet(c.id()).map(ChangeSet::revision).orElse(0L);
                update.changeSet(c.withRevision(stored + 1));
            });
            store.commit(update.build());
            for (LogEntry entry : entries) {
                try {
                    listener.accept(entry);
                } catch (RuntimeException e) {
                    // The log is the record; a listener that fails must not undo or hide a committed operation.
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Builds a {@link GovernanceService}. */
    public static final class Builder {

        private GovernanceStore store;
        private PrincipalResolver principals;
        private Signer signer;
        private GovernancePolicy policy;
        private Clock clock = Clock.systemUTC();
        private Supplier<String> ids = () -> UUID.randomUUID().toString();
        private String issuer = "rulestone-governance";
        private Consumer<LogEntry> listener = entry -> {};

        private Builder() {}

        /** Where the service keeps its state. Required. */
        public Builder store(GovernanceStore store) {
            this.store = Objects.requireNonNull(store, "store");
            return this;
        }

        /** Who is calling. Required. */
        public Builder principals(PrincipalResolver principals) {
            this.principals = Objects.requireNonNull(principals, "principals");
            return this;
        }

        /** What signs approval statements and releases. Required. */
        public Builder signer(Signer signer) {
            this.signer = Objects.requireNonNull(signer, "signer");
            return this;
        }

        /**
         * The policy to start with. A store without a policy gets it, with a {@code POLICY_CHANGED} entry; a
         * store with a policy must hold exactly this one, because a policy changes only through
         * {@link GovernanceService#changePolicy}, which needs an administrator and is logged. Required.
         */
        public Builder policy(GovernancePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** The clock for log entries, statements and releases, and for the business date of retirement; default UTC system time. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** The source of event and statement ids; default random UUIDs. A counter makes them repeatable in tests. */
        public Builder idGenerator(Supplier<String> ids) {
            this.ids = Objects.requireNonNull(ids, "ids");
            return this;
        }

        /** Who signs, as the statements' {@code issuer} field and the service's own log entries name it; default {@code rulestone-governance}. */
        public Builder issuer(String issuer) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            return this;
        }

        /**
         * Receives every log entry after it is committed, such as an alert for {@code POST_APPROVAL_OVERDUE}. A
         * listener that throws is ignored; the log is the record.
         */
        public Builder listener(Consumer<LogEntry> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public GovernanceService build() {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(signer, "signer");
            Objects.requireNonNull(policy, "policy");
            GovernanceService service = new GovernanceService(this);
            service.lock.lock();
            try {
                Optional<GovernancePolicy> stored = store.policy();
                if (stored.isEmpty()) {
                    Tx tx = service.new Tx();
                    tx.event(EventType.POLICY_CHANGED, service.serviceActor(), null, List.of(), policyDetails(0, policy));
                    tx.update.policy(policy);
                    tx.commit();
                } else if (!stored.get().hash().equals(policy.withVersion(stored.get().version()).hash())) {
                    throw new GovernanceException(GovernanceException.Code.INVALID_POLICY,
                            "the store holds policy version " + stored.get().version() + ", which differs from the policy given: "
                                    + "change a policy with changePolicy, as an administrator");
                }
            } finally {
                service.lock.unlock();
            }
            return service;
        }
    }
}
