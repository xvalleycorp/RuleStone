//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RepositoryException;
import vn.io.dxa.rulestone.api.RuleRepository;

/**
 * A {@link RuleRepository} over a directory, such as a Git checkout that a deployment job or the
 * application keeps up to date ({@code git pull}); no Git library is used.
 *
 * <pre>
 * rules/
 *   loan_eligibility.v3.stone.json        tables, flows (.flow.json) and parameter sets (.params.json),
 *   loan_eligibility.v4.stone.json        in any subdirectory, named as you like
 *   retail_credit.v8.params.json
 *   reason-codes.json                     the catalog named reason-codes; cards.reason-codes.json is named cards
 *   approvals/
 *     CHG-2026-0042.approval.jws          every signed approval statement
 *   releases/
 *     retail-credit-prod.release.jws      the current release of each target
 * </pre>
 *
 * <p>{@link #load} returns the release of the target, every approval statement, and the content of
 * every file whose kind, id and version label the release lists, read from the files' {@code id} and
 * {@code version} fields. It selects files by the release without verifying it: the engine verifies
 * the release, recomputes every hash and refuses content that differs from what the release lists.
 * Files that are not valid JSON, and files the release does not list, are left out; directories
 * whose name starts with a dot, such as {@code .git}, are not read.
 */
public final class FileRuleRepository implements RuleRepository {

    /** The directory of approval statements, under the root. */
    public static final String APPROVALS_DIR = "approvals";
    /** The directory of releases, under the root. */
    public static final String RELEASES_DIR = "releases";

    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final String CATALOG_SUFFIX = "." + ReasonCodeCatalogJson.FILE_NAME;

    private final Path root;

    private FileRuleRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** A repository over {@code root}, which is read on every call. */
    public static FileRuleRepository of(Path root) {
        return new FileRuleRepository(root);
    }

    public Path root() {
        return root;
    }

    @Override
    public Snapshot load(String target) {
        Signed release = new Signed(target + RuleStone.RELEASE_FILE_SUFFIX, readRelease(target));
        Optional<Release> listed = ApprovalVerifier.unverifiedRelease(release.jws());
        List<Artifact> artifacts = new ArrayList<>();
        if (listed.isPresent()) {
            Set<String> keys = new HashSet<>();
            listed.get().artifacts().forEach(e -> keys.add(key(e.kind(), e.id(), e.label())));
            for (Artifact artifact : artifacts()) {
                if (keys.contains(key(artifact.kind(), artifact.id(), artifact.version()))) {
                    artifacts.add(artifact);
                }
            }
        }
        List<Signed> approvals = new ArrayList<>();
        Path dir = root.resolve(APPROVALS_DIR);
        if (Files.isDirectory(dir)) {
            for (Path file : list(dir)) {
                String name = file.getFileName().toString();
                if (name.endsWith(RuleStone.APPROVAL_FILE_SUFFIX) && Files.isRegularFile(file)) {
                    approvals.add(new Signed(name, read(file)));
                }
            }
        }
        return new Snapshot(target, listed.map(Release::sequence).orElse(0L), release, artifacts, approvals);
    }

    @Override
    public Optional<Artifact> find(ArtifactKind kind, String id, String exactVersion) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        String label = Release.label(Objects.requireNonNull(exactVersion, "exactVersion"));
        for (Artifact artifact : artifacts()) {
            if (artifact.kind() == kind && artifact.id().equals(id) && artifact.version().equals(label)) {
                try {
                    if (exactVersion(artifact).equals(exactVersion)) {
                        return Optional.of(artifact);
                    }
                } catch (InvalidDecisionException e) {
                    // Not a valid version, so not the one asked for.
                }
            }
        }
        return Optional.empty();
    }

    /** The sequence of the target's release as its file says, unverified; 0 if it cannot be read as a release. */
    @Override
    public long latestSequence(String target) {
        return ApprovalVerifier.unverifiedRelease(readRelease(target)).map(Release::sequence).orElse(0L);
    }

    private String readRelease(String target) {
        Objects.requireNonNull(target, "target");
        if (!TARGET.matcher(target).matches()) {
            throw new IllegalArgumentException("a target is letters, digits, '.', '_' and '-', not starting with '.'");
        }
        Path file = root.resolve(RELEASES_DIR).resolve(target + RuleStone.RELEASE_FILE_SUFFIX);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new RepositoryException("no release of target '" + target + "': " + file + " does not exist");
        } catch (IOException e) {
            throw new RepositoryException("cannot read " + file, e);
        }
    }

    /** Every artifact file under the root, outside the approvals and releases directories, in path order. */
    private List<Artifact> artifacts() {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(f -> Files.isRegularFile(f) && !excluded(root.relativize(f))).sorted().toList();
        } catch (IOException e) {
            throw new RepositoryException("cannot read " + root, e);
        }
        List<Artifact> artifacts = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            ArtifactKind kind = name.endsWith(".stone.json") ? ArtifactKind.TABLE
                    : name.endsWith(DecisionFlowJson.FILE_SUFFIX) ? ArtifactKind.FLOW
                    : name.endsWith(ParameterSetJson.FILE_SUFFIX) ? ArtifactKind.PARAMS
                    : name.equals(ReasonCodeCatalogJson.FILE_NAME) || name.endsWith(CATALOG_SUFFIX) ? ArtifactKind.CATALOG
                    : null;
            if (kind == null) {
                continue;
            }
            String content = read(file);
            JsonNode json;
            try {
                json = DecisionTableJson.MAPPER.readTree(content);
            } catch (JsonProcessingException e) {
                continue;
            }
            String id = kind == ArtifactKind.CATALOG
                    ? (name.equals(ReasonCodeCatalogJson.FILE_NAME) ? ApprovalStatement.CATALOG_ID
                            : name.substring(0, name.length() - CATALOG_SUFFIX.length()))
                    : text(json, "id");
            String version = text(json, "version");
            if (id != null && !id.isEmpty() && version != null) {
                artifacts.add(new Artifact(kind, id, version, root.relativize(file).toString().replace('\\', '/'), content));
            }
        }
        return artifacts;
    }

    private static boolean excluded(Path relative) {
        if (relative.getNameCount() > 1) {
            String first = relative.getName(0).toString();
            if (first.equals(APPROVALS_DIR) || first.equals(RELEASES_DIR)) {
                return true;
            }
        }
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (relative.getName(i).toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    static String exactVersion(Artifact artifact) {
        return switch (artifact.kind()) {
            case TABLE -> DecisionTableJson.exactVersion(DecisionTableJson.read(artifact.content()));
            case FLOW -> DecisionFlowJson.exactVersion(DecisionFlowJson.read(artifact.content()));
            case PARAMS -> ParameterSetJson.exactVersion(ParameterSetJson.read(artifact.content()));
            case CATALOG -> ReasonCodeCatalogJson.exactVersion(ReasonCodeCatalogJson.read(artifact.content()));
        };
    }

    private static String key(ArtifactKind kind, String id, String label) {
        return kind.jsonName() + " " + id + "@" + label;
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        } catch (IOException e) {
            throw new RepositoryException("cannot read " + dir, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RepositoryException("cannot read " + file, e);
        }
    }
}
