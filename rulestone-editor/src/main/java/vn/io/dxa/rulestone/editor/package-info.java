//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * Self-hosted web editor for RuleStone decision tables.
 *
 * <p>{@link vn.io.dxa.rulestone.editor.EditorServer} serves a browser user interface and a JSON
 * API over one directory of {@code .stone.json} files. {@link vn.io.dxa.rulestone.editor.EditorMain}
 * runs it from the command line. The editor has <strong>no authentication</strong>: run it on the
 * user's own machine (it binds to {@code 127.0.0.1} by default) or behind the bank's single sign-on
 * reverse proxy. See {@code docs/editor.md}.
 */
package vn.io.dxa.rulestone.editor;
