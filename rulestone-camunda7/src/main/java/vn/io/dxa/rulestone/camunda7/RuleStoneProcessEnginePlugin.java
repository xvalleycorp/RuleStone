//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * Connects a {@link RuleStoneDecisionService} to a process engine:
 *
 * <ul>
 *   <li>registers it in {@link RuleStoneRegistry} under the engine's name, so that
 *       {@code camunda:class="vn.io.dxa.rulestone.camunda7.RuleStoneDelegate"} finds it;</li>
 *   <li>adds it to the engine's expression beans as {@code rulestone} (see {@link #setBeanName}).
 *       With Spring, expressions resolve Spring beans instead: declare the service as a bean
 *       named {@code rulestone}.</li>
 * </ul>
 *
 * <pre>{@code
 * configuration.getProcessEnginePlugins().add(new RuleStoneProcessEnginePlugin(service));
 * }</pre>
 */
public class RuleStoneProcessEnginePlugin extends AbstractProcessEnginePlugin {

    /** The default expression bean name. */
    public static final String DEFAULT_BEAN_NAME = "rulestone";

    private final RuleStoneDecisionService service;
    private String beanName = DEFAULT_BEAN_NAME;

    /**
     * Creates the plugin.
     *
     * @param service the decision service for this process engine
     */
    public RuleStoneProcessEnginePlugin(RuleStoneDecisionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * Sets the expression bean name, or {@code null} to register no bean.
     *
     * @param beanName the name, by default {@value #DEFAULT_BEAN_NAME}
     */
    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        if (beanName == null) {
            return;
        }
        Map<Object, Object> beans = configuration.getBeans();
        if (beans == null) {
            beans = new HashMap<>();
            configuration.setBeans(beans);
        }
        beans.put(beanName, service);
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        RuleStoneRegistry.register(processEngine.getName(), service);
    }

    @Override
    public String toString() {
        return "RuleStoneProcessEnginePlugin";
    }
}
