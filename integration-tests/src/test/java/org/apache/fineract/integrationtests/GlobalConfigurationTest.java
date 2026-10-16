/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.client.feign.util.CallFailedRuntimeException;
import org.apache.fineract.client.models.GetGlobalConfigurationsResponse;
import org.apache.fineract.client.models.GlobalConfigurationPropertyData;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignGlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.FineractFeignClientHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "unchecked" })

public class GlobalConfigurationTest {

    private FeignGlobalConfigurationHelper globalConfigurationHelper;

    @BeforeEach
    public void setup() {
        globalConfigurationHelper = new FeignGlobalConfigurationHelper(FineractFeignClientHelper.getFineractFeignClient());
    }

    @AfterEach
    public void tearDown() {
        globalConfigurationHelper.resetAllDefaultGlobalConfigurations();
        globalConfigurationHelper.verifyAllDefaultGlobalConfigurations();
    }

    @Test
    public void testGlobalConfigurations() {
        // Retrieving All Global Configuration details
        final GetGlobalConfigurationsResponse globalConfig = globalConfigurationHelper.retrieveAllConfigurations();
        Assertions.assertNotNull(globalConfig);
    }

    @Test
    public void testGlobalConfigurationUpdate() {
        String configName = GlobalConfigurationConstants.PENALTY_WAIT_PERIOD;
        GlobalConfigurationPropertyData config = globalConfigurationHelper.retrieveConfigurationByName(configName);
        try {
            Assertions.assertNotNull(config);
            Assertions.assertNotNull(config.getValue());
            Long newValue = config.getValue() + 1;
            Assertions.assertNotNull(config.getEnabled());
            // Updating Value for penalty-wait-period Global Configuration
            boolean newEnabledValue = !config.getEnabled();
            // Updating Enabled Flag for penalty-wait-period Global
            // Configuration
            globalConfigurationHelper.updateGlobalConfiguration(config.getName(),
                    new PutGlobalConfigurationsRequest().value(newValue).enabled(newEnabledValue));
            GlobalConfigurationPropertyData updatedConfiguration = globalConfigurationHelper.retrieveConfigurationByName(configName);
            // Verifying Value for penalty-wait-period after the update
            Assertions.assertEquals(newValue, updatedConfiguration.getValue(), "Verifying Global Config Value after the update");
            // Verifying Enabled Flag for penalty-wait-period after Updation
            Assertions.assertEquals(newEnabledValue, updatedConfiguration.getEnabled(),
                    "Verifying Enabled Flag Global Config after the update");
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(config.getName(),
                    new PutGlobalConfigurationsRequest().value(config.getValue()).enabled(config.getEnabled()));
        }
    }

    @Test
    public void testGetConfigurationPropertyById() {
        Long configId = 1L;
        GlobalConfigurationPropertyData configuration = assertDoesNotThrow(
                () -> globalConfigurationHelper.retrieveConfigurationById(configId));
        assertTrue(StringUtils.isNotBlank(configuration.getName()));
    }

    @Test
    public void testNotExistingConfigurationProperty() {
        String notExistingConfigName = "not-existing-configuration";
        CallFailedRuntimeException exception = globalConfigurationHelper.retrieveConfigurationByNameExpectingError(notExistingConfigName);
        assertEquals(404, exception.getStatus());
    }

    @Test
    public void testPasswordUpdateFailing() {
        String configName = GlobalConfigurationConstants.FORCE_PASSWORD_RESET_DAYS;
        GlobalConfigurationPropertyData config = globalConfigurationHelper.retrieveConfigurationByName(configName);
        Assertions.assertNotNull(config);

        CallFailedRuntimeException exception = globalConfigurationHelper.updateGlobalConfigurationExpectingError(configName,
                new PutGlobalConfigurationsRequest().enabled(true).value(null));
        assertEquals(403, exception.getStatus());
        assertTrue(exception.getMessage().contains("error.msg.password.reset.days.value.must.be.greater.than.zero"));
    }

    @Test
    public void testOriginatorCreationConfigurationExists() {
        String configName = GlobalConfigurationConstants.ENABLE_ORIGINATOR_CREATION_DURING_LOAN_APPLICATION;
        GlobalConfigurationPropertyData config = globalConfigurationHelper.retrieveConfigurationByName(configName);

        Assertions.assertNotNull(config, "Configuration should exist");
        assertEquals(configName, config.getName(), "Configuration name should match");
        assertEquals(false, config.getEnabled(), "Configuration should be disabled by default");
        assertEquals(false, config.getTrapDoor(), "Configuration should not be a trap door");
    }

    @Test
    public void testOriginatorCreationConfigurationCanBeEnabled() {
        String configName = GlobalConfigurationConstants.ENABLE_ORIGINATOR_CREATION_DURING_LOAN_APPLICATION;
        GlobalConfigurationPropertyData config = globalConfigurationHelper.retrieveConfigurationByName(configName);
        Assertions.assertNotNull(config);

        try {
            globalConfigurationHelper.updateGlobalConfiguration(configName, new PutGlobalConfigurationsRequest().enabled(true));
            GlobalConfigurationPropertyData enabledConfig = globalConfigurationHelper.retrieveConfigurationByName(configName);
            assertEquals(true, enabledConfig.getEnabled(), "Configuration should be enabled after update");

            globalConfigurationHelper.updateGlobalConfiguration(configName, new PutGlobalConfigurationsRequest().enabled(false));
            GlobalConfigurationPropertyData disabledConfig = globalConfigurationHelper.retrieveConfigurationByName(configName);
            assertEquals(false, disabledConfig.getEnabled(), "Configuration should be disabled after update");
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(configName,
                    new PutGlobalConfigurationsRequest().enabled(config.getEnabled()));
        }
    }

    @Test
    public void testInstantDelinquencyCalculationConfigurationExists() {
        String configName = GlobalConfigurationConstants.ENABLE_INSTANT_DELINQUENCY_CALCULATION;
        GlobalConfigurationPropertyData config = globalConfigurationHelper.retrieveConfigurationByName(configName);

        Assertions.assertNotNull(config, "Configuration should exist");
        assertEquals(configName, config.getName(), "Configuration name should match");
        assertEquals(true, config.getEnabled(), "Configuration should be enabled by default");
        assertEquals(false, config.getTrapDoor(), "Configuration should not be a trap door");
    }

    @Test
    public void testInstantDelinquencyCalculationConfigurationCanBeEnabled() {
        String configName = GlobalConfigurationConstants.ENABLE_INSTANT_DELINQUENCY_CALCULATION;
        GlobalConfigurationPropertyData config = globalConfigurationHelper.retrieveConfigurationByName(configName);
        Assertions.assertNotNull(config);

        try {
            globalConfigurationHelper.updateGlobalConfiguration(configName, new PutGlobalConfigurationsRequest().enabled(true));
            GlobalConfigurationPropertyData enabledConfig = globalConfigurationHelper.retrieveConfigurationByName(configName);
            assertEquals(true, enabledConfig.getEnabled(), "Configuration should be enabled after update");

            globalConfigurationHelper.updateGlobalConfiguration(configName, new PutGlobalConfigurationsRequest().enabled(false));
            GlobalConfigurationPropertyData disabledConfig = globalConfigurationHelper.retrieveConfigurationByName(configName);
            assertEquals(false, disabledConfig.getEnabled(), "Configuration should be disabled after update");
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(configName,
                    new PutGlobalConfigurationsRequest().enabled(config.getEnabled()));
        }
    }
}
