//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.core.DecisionTable;

/**
 * The decision tables registered with the engine, by id. Selects the table whose input types
 * describe a task's variables, exactly as the engine selects the version it evaluates.
 */
final class DecisionTables {

    private final Map<String, List<DecisionTable>> byId;

    DecisionTables(Collection<DecisionTable> tables) {
        Map<String, List<DecisionTable>> map = new LinkedHashMap<>();
        for (DecisionTable table : tables) {
            map.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(table);
        }
        map.replaceAll((id, versions) -> List.copyOf(versions));
        this.byId = Map.copyOf(map);
    }

    /**
     * The table for {@code id}: the given version, or else the version effective on {@code date}.
     *
     * @throws DecisionNotFoundException if there is none
     */
    DecisionTable select(String id, Optional<String> version, LocalDate date) {
        List<DecisionTable> versions = byId.get(id);
        if (versions == null) {
            throw new DecisionNotFoundException("unknown decision '" + id + "'");
        }
        if (version.isPresent()) {
            return versions.stream()
                    .filter(t -> t.version().equals(version.get()))
                    .findFirst()
                    .orElseThrow(() -> new DecisionNotFoundException(
                            "decision '" + id + "' has no version '" + version.get() + "'"));
        }
        return versions.stream()
                .filter(t -> t.isEffectiveOn(date))
                .findFirst()
                .orElseThrow(() -> new DecisionNotFoundException(
                        "no version of decision '" + id + "' is effective on " + date));
    }
}
