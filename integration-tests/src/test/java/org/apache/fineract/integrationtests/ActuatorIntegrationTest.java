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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.integrationtests.client.feign.helpers.ServerPagesApi;
import org.apache.fineract.integrationtests.common.FineractFeignClientHelper;
import org.junit.jupiter.api.Test;

@Slf4j
public class ActuatorIntegrationTest {

    private final ServerPagesApi serverPagesApi = FineractFeignClientHelper.createProviderRootFeignClient().create(ServerPagesApi.class);

    @Test
    public void testActuatorGitBuildInfo() {
        Map<String, Object> info = serverPagesApi.actuatorInfo();

        @SuppressWarnings("unchecked")
        Map<String, Object> gitBuildInfo = (Map<String, Object>) info.get("git");

        assertTrue(gitBuildInfo.containsKey("branch"));
        assertTrue(gitBuildInfo.containsKey("remote"));

    }

}
