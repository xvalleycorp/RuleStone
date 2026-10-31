//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;

/**
 * The directory of {@code .stone.json} files the editor works on. The only class that touches the file system.
 *
 * <p>Files are named by their path relative to the directory, with {@code /} separators, e.g.
 * {@code loans/loan_eligibility.v3.stone.json}. A name is accepted only if every segment starts with a
 * letter or digit and contains only letters, digits, {@code _ . -}; it ends in {@code .stone.json}; it
 * is at most {@value #MAX_DEPTH} directories deep; and it resolves, without following symbolic links,
 * to a regular file inside the directory. So {@code ..}, absolute paths, hidden files, backslashes,
 * encoded separators and symbolic links pointing elsewhere are all refused.
 *
 * <p>The {@code .params.json} parameter sets in the directory, read with the same rules, are
 * registered with every table the editor checks or evaluates; the editor does not edit them.
 */
final class RulesDirectory {

    static final String SUFFIX = ".stone.json";
    static final int MAX_DEPTH = 8;
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    private final Path root;
    private final Object writeLock = new Object();

    /** @throws IllegalArgumentException if {@code dir} is not an existing directory */
    RulesDirectory(Path dir) {
        try {
            this.root = dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("rules directory does not exist: " + dir, e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
    }

    Path root() {
        return root;
    }

    /** Every {@code .stone.json} file, as relative names, sorted. Hidden directories (e.g. {@code .git}) are skipped. */
    List<String> list() {
        return list(SUFFIX);
    }

    /**
     * Every {@code .params.json} parameter set in the directory that can be read, in file name order.
     * A file that cannot be read is left out; tables that read it then report the set as not registered.
     */
    List<ParameterSet> paramSets() {
        List<ParameterSet> sets = new ArrayList<>();
        for (String name : list(ParameterSetJson.FILE_SUFFIX)) {
            try {
                Path file = root.resolve(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= MAX_FILE_BYTES) {
                    sets.add(ParameterSetJson.read(Files.readString(file, StandardCharsets.UTF_8)));
                }
            } catch (IOException | RuntimeException e) {
                // left out, as documented
            }
        }
        return sets;
    }

    private List<String> list(String suffix) {
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, java.util.Set.of(), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && !SEGMENT.matcher(dir.getFileName().toString()).matches()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        String name = relative(file);
                        if (isValidName(name, suffix)) {
                            files.add(name);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Collections.sort(files);
        return files;
    }

    boolean exists(String name) {
        return Files.isRegularFile(resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Reads a file as UTF-8.
     *
     * @throws ApiException 400 for a refused name, 404 if there is no such file, 413 if it is too large
     */
    String read(String name) {
        Path file = existing(name);
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new ApiException(413, "file_too_large", "file is larger than " + MAX_FILE_BYTES + " bytes: " + name);
            }
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(422, "not_utf8", "file is not valid UTF-8: " + name);
        } catch (NoSuchFileException e) {
            throw new ApiException(404, "not_found", "no such file: " + name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes a file atomically (a temporary file in the same directory, then a rename).
     *
     * @param expectedEtag if not {@code null}, the file must still have this {@link #etag}, otherwise 412
     * @param create       if {@code true}, the file must not exist yet (409 if it does); its directory must exist
     * @return the new etag
     */
    String write(String name, String content, String expectedEtag, boolean create) {
        Path file = resolve(name);
        synchronized (writeLock) {
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            if (create && exists) {
                throw new ApiException(409, "already_exists", "file already exists: " + name);
            }
            if (!create) {
                existing(name);
                if (expectedEtag != null && !expectedEtag.equals(etag(read(name)))) {
                    throw new ApiException(412, "modified",
                            "file was changed since it was loaded (by another user or a Git operation): " + name);
                }
            }
            checkParent(file, name);
            Path temp = file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                if (create) {
                    Files.move(temp, file);
                } else {
                    try {
                        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (FileAlreadyExistsException e) {
                throw new ApiException(409, "already_exists", "file already exists: " + name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort: the temporary file is hidden and never listed
                }
            }
        }
        return etag(content);
    }

    /** Hex SHA-256 of the file content; used for optimistic locking ({@code ETag}/{@code If-Match}). */
    static String etag(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether {@code name} is acceptable as a file name, without touching the file system. */
    static boolean isValidName(String name) {
        return isValidName(name, SUFFIX);
    }

    private static boolean isValidName(String name, String suffix) {
        if (name == null || name.isEmpty() || name.length() > 512 || !name.endsWith(suffix)) {
            return false;
        }
        String[] segments = name.split("/", -1);
        if (segments.length > MAX_DEPTH + 1) {
            return false;
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return segments[segments.length - 1].length() > suffix.length();
    }

    /** Resolves a name to a path inside the directory, or throws 400. The file need not exist. */
    Path resolve(String name) {
        if (!isValidName(name)) {
            throw new ApiException(400, "invalid_file_name",
                    "file names must be relative paths of letters, digits, '_', '.', '-' ending in " + SUFFIX);
        }
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new ApiException(400, "invalid_file_name", "file is outside the rules directory");
        }
        return file;
    }

    private Path existing(String name) {
        Path file = resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(404, "not_found", "no such file: " + name);
        }
        checkParent(file, name);
        return file;
    }

    /** The containing directory must exist and really be inside the root (no symbolic link leading out). */
    private void checkParent(Path file, String name) {
        try {
            Path parent = file.getParent().toRealPath();
            if (!parent.startsWith(root) || !parent.equals(file.getParent())) {
                throw new ApiException(400, "invalid_file_name", "file is outside the rules directory");
            }
        } catch (NoSuchFileException e) {
            throw new ApiException(404, "not_found", "no such directory for: " + name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }
}
