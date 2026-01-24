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

package org.apache.fineract.infrastructure.core.config;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

@Configuration
public class SpringConfig {

    @Bean(name = "fineractEventExecutor")
    public ThreadPoolTaskExecutor fineractEventExecutor() {
        ThreadPoolTaskExecutor threadPool = new ThreadPoolTaskExecutor();
        threadPool.setCorePoolSize(20);
        threadPool.setMaxPoolSize(100);
        threadPool.setQueueCapacity(Integer.MAX_VALUE);
        threadPool.setAllowCoreThreadTimeOut(true);
        threadPool.setThreadNamePrefix("FineractEvent-");
        threadPool.setWaitForTasksToCompleteOnShutdown(true);
        threadPool.setAwaitTerminationSeconds(60);
        threadPool.setTaskDecorator(new FineractContextAwareTaskDecorator());
        return threadPool;
    }

    @Bean
    @DependsOn("overrideSecurityContextHolderStrategy")
    public SimpleApplicationEventMulticaster applicationEventMulticaster(
            @Qualifier("fineractEventExecutor") ThreadPoolTaskExecutor taskExecutor) {
        SimpleApplicationEventMulticaster saem = new SimpleApplicationEventMulticaster();
        DelegatingSecurityContextAsyncTaskExecutor securityExecutor =
                new DelegatingSecurityContextAsyncTaskExecutor(taskExecutor);
        saem.setTaskExecutor(securityExecutor);
        return saem;
    }

    @Bean
    public MethodInvokingFactoryBean overrideSecurityContextHolderStrategy() {
        MethodInvokingFactoryBean mifb = new MethodInvokingFactoryBean();
        mifb.setTargetClass(SecurityContextHolder.class);
        mifb.setTargetMethod("setStrategyName");
        mifb.setArguments(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
        return mifb;
    }

    @Bean
    @DependsOn("overrideSecurityContextHolderStrategy")
    public SecurityContextHolderStrategy securityContextHolderStrategy() {
        return SecurityContextHolder.getContextHolderStrategy();
    }

    static class FineractContextAwareTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
            final String authToken = ThreadLocalContextUtil.getAuthToken();

            HashMap<BusinessDateType, LocalDate> businessDates;
            try {
                final Map<BusinessDateType, LocalDate> parentBusinessDates = ThreadLocalContextUtil.getBusinessDates();
                businessDates = parentBusinessDates != null ? new HashMap<>(parentBusinessDates) : new HashMap<>();
            } catch (IllegalArgumentException e) {
                businessDates = new HashMap<>();
            }

            final HashMap<BusinessDateType, LocalDate> capturedBusinessDates = businessDates;

            return () -> {
                final boolean hasContext = tenant != null || authToken != null || !capturedBusinessDates.isEmpty();
                try {
                    if (hasContext) {
                        if (tenant != null) {
                            ThreadLocalContextUtil.setTenant(tenant);
                        }
                        if (authToken != null) {
                            ThreadLocalContextUtil.setAuthToken(authToken);
                        }
                        if (!capturedBusinessDates.isEmpty()) {
                            ThreadLocalContextUtil.setBusinessDates(capturedBusinessDates);
                        }
                    }
                    runnable.run();
                } finally {
                    if (hasContext) {
                        ThreadLocalContextUtil.reset();
                    }
                }
            };
        }
    }
}
