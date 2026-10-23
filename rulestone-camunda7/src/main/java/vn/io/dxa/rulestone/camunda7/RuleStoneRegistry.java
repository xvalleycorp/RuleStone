//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decision service of each process engine, by process engine name. {@link RuleStoneProcessEnginePlugin}
 * registers here, and {@link RuleStoneDelegate} looks up the service of the engine executing it.
 */
public final class RuleStoneRegistry {

    private static final Map<String, RuleStoneDecisionService> SERVICES = new ConcurrentHashMap<>();

    private RuleStoneRegistry() {
    }

    /**
     * Registers the service for a process engine, replacing any previous one.
     *
     * @param processEngineName the process engine's name, e.g. {@code default}
     * @param service           the decision service
     */
    public static void register(String processEngineName, RuleStoneDecisionService service) {
        SERVICES.put(Objects.requireNonNull(processEngineName, "processEngineName"),
                Objects.requireNonNull(service, "service"));
    }

    /**
     * Removes the service of a process engine, e.g. when the engine is closed.
     *
     * @param processEngineName the process engine's name
     * @return the removed service, if there was one
     */
    public static Optional<RuleStoneDecisionService> unregister(String processEngineName) {
        return Optional.ofNullable(SERVICES.remove(processEngineName));
    }

    /**
     * The service of a process engine.
     *
     * @param processEngineName the process engine's name
     * @return the service, if one is registered
     */
    public static Optional<RuleStoneDecisionService> find(String processEngineName) {
        return Optional.ofNullable(SERVICES.get(processEngineName));
    }
}
