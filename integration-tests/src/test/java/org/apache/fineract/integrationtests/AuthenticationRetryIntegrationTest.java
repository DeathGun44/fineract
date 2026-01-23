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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.client.models.PostUsersRequest;
import org.apache.fineract.client.models.PostUsersResponse;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.client.IntegrationTest;
import org.apache.fineract.integrationtests.common.GlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.useradministration.users.UserHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AuthenticationRetryIntegrationTest extends IntegrationTest {

    private static final int MAX_RETRIES = 2;
    private static final String TEST_PASSWORD = "Abcdef1#2$3%XYZ";

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private GlobalConfigurationHelper globalConfigurationHelper;
    private final List<Long> transientUserIds = new ArrayList<>();

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.globalConfigurationHelper = new GlobalConfigurationHelper();
    }

    @AfterEach
    public void tearDown() {
        globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.MAX_FAILED_LOGIN_ATTEMPTS,
                new PutGlobalConfigurationsRequest().value(3L));
        globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.MAX_FAILED_LOGIN_ATTEMPTS, false);

        for (Long userId : this.transientUserIds) {
            UserHelper.deleteUser(this.requestSpec, expectStatusCode(200), userId.intValue());
        }
        this.transientUserIds.clear();
    }

    @Test
    public void testAccountLockoutAfterMaxFailedAttempts() {
        enableLoginRetryLimit(MAX_RETRIES);
        String username = createTransientTestUser();

        attemptLoginExpecting(username, "wrongpassword", 401);
        attemptLoginExpecting(username, "wrongpassword", 401);

        attemptLoginExpecting(username, TEST_PASSWORD, 401);
    }

    @Test
    public void testSuccessfulLoginResetsFailedAttemptCounter() {
        enableLoginRetryLimit(MAX_RETRIES);
        String username = createTransientTestUser();

        attemptLoginExpecting(username, "wrongpassword", 401);
        attemptLoginExpecting(username, TEST_PASSWORD, 200);

        attemptLoginExpecting(username, "wrongpassword", 401);
        attemptLoginExpecting(username, TEST_PASSWORD, 200);
    }

    private void enableLoginRetryLimit(int maxRetries) {
        globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.MAX_FAILED_LOGIN_ATTEMPTS,
                new PutGlobalConfigurationsRequest().value((long) maxRetries));
        globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.MAX_FAILED_LOGIN_ATTEMPTS, true);
    }

    private String createTransientTestUser() {
        PostUsersRequest userRequest = UserHelper.buildUserRequest(responseSpec, requestSpec, TEST_PASSWORD);
        PostUsersResponse userResponse = UserHelper.createUser(requestSpec, responseSpec, userRequest);

        assertNotNull(userResponse.getResourceId(), "User creation failed to return an ID");
        this.transientUserIds.add(userResponse.getResourceId());

        return userRequest.getUsername();
    }

    private void attemptLoginExpecting(String username, String password, int expectedStatusCode) {
        String requestBody = String.format("{\"username\":\"%s\", \"password\":\"%s\"}", username, password);

        int actualStatusCode = RestAssured.given().contentType(ContentType.JSON).body(requestBody)
                .post("/fineract-provider/api/v1/authentication?" + Utils.TENANT_IDENTIFIER).getStatusCode();

        assertEquals(expectedStatusCode, actualStatusCode,
                String.format("Expected status %d but got %d for user '%s'", expectedStatusCode, actualStatusCode, username));
    }

    private ResponseSpecification expectStatusCode(int code) {
        return new ResponseSpecBuilder().expectStatusCode(code).build();
    }
}
