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
package org.apache.fineract.infrastructure.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserLockoutServiceImpl implements UserLockoutService {

    private final AppUserRepository appUserRepository;
    private final ConfigurationDomainService configurationDomainService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailedLogin(String username) {
        if (!configurationDomainService.isLoginRetryLimitEnabled()) {
            return;
        }

        AppUser user = appUserRepository.findAppUserByName(username);

        if (user != null) {
            if (!user.isAccountNonLocked()) {
                return;
            }

            user.incrementFailedLoginAttempts();
            Long maxRetries = configurationDomainService.retrieveMaxFailedLoginAttempts();
            if (maxRetries != null && user.getFailedLoginAttempts() >= maxRetries) {
                user.updateAccountLocked(true);
                log.warn("Security Alert: User {} locked after {} failed attempts.", username, maxRetries);
            }
            appUserRepository.save(user);
        }
    }

    @Override
    @Transactional
    public void handleSuccessfulLogin(String username) {
        AppUser user = appUserRepository.findAppUserByName(username);
        if (user != null && user.getFailedLoginAttempts() > 0) {
            user.resetFailedLoginAttempts();
            appUserRepository.save(user);
        }
    }
}
