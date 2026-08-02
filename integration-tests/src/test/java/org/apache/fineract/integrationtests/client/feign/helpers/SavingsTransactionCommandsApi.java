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

import feign.Headers;
import feign.Param;
import feign.RequestLine;
import org.apache.fineract.client.models.CommandProcessingResult;
import org.apache.fineract.client.models.PostSavingsAccountBulkReversalTransactionsRequest;

/**
 * Adjusts a savings transaction - {@code reverse}, {@code undo} or {@code releaseAmount}. Hand-written because
 * {@code SavingsAccountTransactionsApiResource} documents the 200 as an array of request models, so the generated
 * client cannot read the {@code resourceId} these tests assert on. Correcting that {@code @ApiResponse} is a breaking
 * OpenAPI change, so it is prepared separately rather than carried by a test migration.
 */
@Headers({ "Accept: application/json", "Content-Type: application/json" })
public interface SavingsTransactionCommandsApi {

    @RequestLine("POST /v1/savingsaccounts/{savingsId}/transactions/{transactionId}?command={command}")
    CommandProcessingResult adjustSavingsAccountTransaction(@Param("savingsId") Long savingsId, @Param("transactionId") Long transactionId,
            @Param("command") String command, PostSavingsAccountBulkReversalTransactionsRequest request);
}
