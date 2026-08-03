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
package org.apache.fineract.integrationtests.common.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import org.apache.fineract.client.feign.ObjectMapperFactory;
import org.apache.fineract.client.feign.services.BatchApiApi;
import org.apache.fineract.client.feign.util.CallFailedRuntimeException;
import org.apache.fineract.client.feign.util.FeignCalls;
import org.apache.fineract.client.models.BatchRequest;
import org.apache.fineract.client.models.BatchResponse;
import org.apache.fineract.integrationtests.common.FineractFeignClientHelper;

/**
 * Integration-test helper for Batch Service using the generated Feign client.
 */
public class BatchServiceHelper {

    public BatchServiceHelper() {}

    private static BatchApiApi api() {
        return FineractFeignClientHelper.getFineractFeignClient().batch();
    }

    public List<BatchResponse> handleBatch(BatchRequest batchRequest, boolean enclosingTransaction) {
        return handleBatch(List.of(batchRequest), enclosingTransaction);
    }

    public List<BatchResponse> handleBatch(List<BatchRequest> batchRequests, boolean enclosingTransaction) {
        return FeignCalls.ok(() -> api().handleBatchRequests(batchRequests, enclosingTransaction));
    }

    /** A failed batch raises on the sub-request's status code, but the body is still the sub-response list. */
    public List<BatchResponse> handleBatchAllowingFailure(List<BatchRequest> batchRequests, boolean enclosingTransaction) {
        try {
            return handleBatch(batchRequests, enclosingTransaction);
        } catch (CallFailedRuntimeException e) {
            return parseBatchResponses(e);
        }
    }

    private static List<BatchResponse> parseBatchResponses(CallFailedRuntimeException failure) {
        try {
            return ObjectMapperFactory.getShared().readValue(failure.getResponseBody(), new TypeReference<List<BatchResponse>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Batch call failed with status " + failure.getStatus()
                    + " and a body that is not a list of batch responses: " + failure.getResponseBody(), e);
        }
    }
}
