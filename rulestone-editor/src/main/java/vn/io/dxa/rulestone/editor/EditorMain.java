//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar rulestone-editor-&lt;version&gt;-all.jar --rules-dir ./rules [--port 8080] [--host 127.0.0.1]
 *      [--allowed-host rules.bank.example] [--max-body-bytes 1048576]
 * </pre>
 */
public final class EditorMain {

    private static final String USAGE = """
            Usage: java -jar rulestone-editor.jar --rules-dir <dir> [options]

              --rules-dir <dir>        directory of .stone.json files to edit (required)
              --port <n>               port to listen on (default 8080; 0 = any free port)
              --host <address>         address to listen on (default 127.0.0.1)
              --allowed-host <name>    accept this Host header, e.g. the reverse proxy's name; repeatable
              --max-body-bytes <n>     largest accepted request body (default 1048576)
              --help                   show this help

            The editor has NO authentication. Keep the default 127.0.0.1, or run it behind
            your organization's single sign-on reverse proxy. See docs/editor.md.
            """;

    private EditorMain() {}

    /**
     * Starts the editor and blocks until the process is stopped.
     *
     * @param args command line arguments, see {@code --help}
     */
    public static void main(String[] args) {
        // Bound how long a slow client can hold a connection (seconds); read before the first server is created.
        setDefault("sun.net.httpserver.maxReqTime", "30");
        setDefault("sun.net.httpserver.maxRspTime", "60");
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Parses arguments and starts the server; returns a non-zero exit status on error, otherwise blocks. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        EditorServer.Builder builder = EditorServer.builder();
        boolean hasDir = false;
        String host = "127.0.0.1";
        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    case "--rules-dir" -> {
                        builder.rulesDir(Path.of(value(args, ++i, arg)));
                        hasDir = true;
                    }
                    case "--port" -> builder.port(Integer.parseInt(value(args, ++i, arg)));
                    case "--host" -> {
                        host = value(args, ++i, arg);
                        builder.host(host);
                    }
                    case "--allowed-host" -> builder.allowedHost(value(args, ++i, arg));
                    case "--max-body-bytes" -> builder.maxBodyBytes(Long.parseLong(value(args, ++i, arg)));
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
            }
            if (!hasDir) {
                throw new IllegalArgumentException("--rules-dir is required");
            }
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE);
            return 2;
        }
        EditorServer server;
        try {
            server = builder.start();
        } catch (IOException | IllegalArgumentException e) {
            err.println("error: cannot start the editor: " + e.getMessage());
            return 1;
        }
        out.println("RuleStone Editor " + Catalog.editorVersion() + " editing " + server.rulesDir());
        out.println("Open " + server.uri());
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
            err.println("WARNING: listening on " + host + ". The editor has no authentication: anyone who can reach "
                    + "this port can change the rules. Put it behind your single sign-on reverse proxy.");
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "rulestone-editor-shutdown"));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void setDefault(String property, String value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, value);
        }
    }
}
