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
package org.apache.fineract.integrationtests.client.feign.helpers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.integrationtests.client.feign.modules.LoanTestData;
import org.apache.fineract.integrationtests.common.Utils;

public final class FeignGroupCenterHelper {

    private static final Gson GSON = new Gson();

    private FeignGroupCenterHelper() {}

    public static Long createGroup(int officeId) {
        Map<String, Object> map = new HashMap<>();
        map.put("officeId", officeId);
        map.put("name", Utils.uniqueRandomStringGenerator("Group_Name_", 5));
        map.put("externalId", UUID.randomUUID().toString());
        map.put("dateFormat", LoanTestData.DATETIME_PATTERN);
        map.put("locale", LoanTestData.LOCALE);
        map.put("active", true);
        map.put("activationDate", "04 March 2011");
        return extractResourceId(FeignRawHttpHelper.post("/groups", GSON.toJson(map)));
    }

    public static Long createStaff(int officeId) {
        Map<String, Object> map = new HashMap<>();
        map.put("locale", LoanTestData.LOCALE);
        map.put("dateFormat", LoanTestData.DATETIME_PATTERN);
        map.put("joiningDate", "20 September 2011");
        map.put("officeId", officeId);
        map.put("firstname", Utils.uniqueRandomStringGenerator("michael_", 5));
        map.put("lastname", Utils.uniqueRandomStringGenerator("Doe_", 4));
        map.put("isLoanOfficer", true);
        return extractResourceId(FeignRawHttpHelper.post("/staff", GSON.toJson(map)));
    }

    public static Long createCenter(String name, int officeId, String externalId, int staffId, long[] groupMembers, String activationDate) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("officeId", officeId);
        map.put("externalId", externalId);
        map.put("staffId", staffId);
        map.put("groupMembers", groupMembers);
        map.put("active", true);
        map.put("locale", LoanTestData.LOCALE);
        map.put("dateFormat", LoanTestData.DATETIME_PATTERN);
        map.put("activationDate", activationDate);
        return extractResourceId(FeignRawHttpHelper.post("/centers", GSON.toJson(map)));
    }

    public static JsonObject retrieveCenter(long centerId) {
        return JsonParser.parseString(FeignRawHttpHelper.get("/centers/" + centerId + "?associations=groupMembers")).getAsJsonObject();
    }

    public static void associateClientToGroup(long groupId, long clientId) {
        Map<String, List<String>> map = Map.of("clientMembers", List.of(String.valueOf(clientId)));
        FeignRawHttpHelper.post("/groups/" + groupId + "?command=associateClients", GSON.toJson(map));
    }

    public static Long createCollateralProduct() {
        Map<String, String> map = new HashMap<>();
        map.put("name", Utils.randomStringGenerator("COLLATERAL_PRODUCT", 5));
        map.put("currency", "USD");
        map.put("unitType", "acre");
        map.put("quality", "agriculture");
        map.put("pctToBase", BigDecimal.valueOf(40).toString());
        map.put("basePrice", BigDecimal.valueOf(100000000).toString());
        map.put("locale", LoanTestData.LOCALE);
        return extractResourceId(FeignRawHttpHelper.post("/collateral-management", GSON.toJson(map)));
    }

    public static Long createClientCollateral(long clientId, long collateralId) {
        Map<String, String> map = new HashMap<>();
        map.put("collateralId", String.valueOf(collateralId));
        map.put("quantity", BigDecimal.valueOf(100).toString());
        map.put("locale", LoanTestData.LOCALE);
        return extractResourceId(FeignRawHttpHelper.post("/clients/" + clientId + "/collaterals", GSON.toJson(map)));
    }

    private static Long extractResourceId(String response) {
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        return json.get("resourceId").getAsLong();
    }
}
