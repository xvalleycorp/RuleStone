//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses requests that do not come from the editor's own pages.
 *
 * <ul>
 *   <li><b>Host</b> must be one of the names the editor is served under: {@code localhost}, {@code 127.0.0.1},
 *       {@code [::1]} or the bind address with the actual port, plus every configured allowed host. This defeats
 *       DNS rebinding, where a hostile site points its own name at 127.0.0.1.</li>
 *   <li><b>Origin</b>, when the browser sends one, must be the same host and port as {@code Host}. Browsers send it
 *       with every cross-site and every state-changing request, so another site cannot make the user's browser save
 *       a table (CSRF). {@code Origin: null} is refused.</li>
 *   <li>Without {@code Origin}, a {@code Sec-Fetch-Site} other than {@code same-origin} or {@code none} on an API
 *       request is refused. Requests with neither header (curl, scripts) are allowed: they are not driven by a
 *       web page.</li>
 * </ul>
 * In addition, bodies must be {@code application/json}, which a cross-site HTML form cannot send, and the editor
 * never answers CORS preflight requests.
 */
final class RequestGuard {

    private final Set<String> allowedHosts;

    /**
     * @param bindHost     the address the server listens on
     * @param port         the actual port
     * @param extraHosts   additional {@code Host} values to accept, e.g. {@code rules.bank.example} behind a proxy
     */
    RequestGuard(String bindHost, int port, Set<String> extraHosts) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String loopback : new String[] {"localhost", "127.0.0.1", "[::1]"}) {
            hosts.add(loopback + ":" + port);
        }
        if (!isWildcard(bindHost)) {
            String host = bindHost.contains(":") && !bindHost.startsWith("[") ? "[" + bindHost + "]" : bindHost;
            hosts.add(host.toLowerCase(Locale.ROOT) + ":" + port);
        }
        extraHosts.forEach(h -> hosts.add(h.strip().toLowerCase(Locale.ROOT)));
        this.allowedHosts = Set.copyOf(hosts);
    }

    Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** @throws ApiException 403 (or 421 for an unknown Host) if the request must be refused */
    void check(String host, String origin, String fetchSite, boolean api) {
        String normalizedHost = host == null ? null : host.strip().toLowerCase(Locale.ROOT);
        if (normalizedHost == null || !allowedHosts.contains(normalizedHost)) {
            throw new ApiException(421, "unknown_host",
                    "this editor is not served under that host name; see --allowed-host in docs/editor.md");
        }
        if (origin != null) {
            String originHost = originHost(origin);
            if (originHost == null || !originHost.equals(normalizedHost)) {
                throw new ApiException(403, "cross_origin", "cross-origin requests are not allowed");
            }
        } else if (api && fetchSite != null
                && !fetchSite.equalsIgnoreCase("same-origin") && !fetchSite.equalsIgnoreCase("none")) {
            throw new ApiException(403, "cross_origin", "cross-site requests are not allowed");
        }
    }

    /** {@code host[:port]} of an Origin header, lower case; the port only if explicit. */
    private static String originHost(String origin) {
        try {
            URI uri = new URI(origin.strip());
            if (uri.getHost() == null || uri.getScheme() == null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    static boolean isWildcard(String host) {
        return host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]");
    }
}
