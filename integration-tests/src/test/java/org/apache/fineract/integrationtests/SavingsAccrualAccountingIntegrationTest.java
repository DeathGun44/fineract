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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.fineract.client.models.JournalEntryTransactionItem;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.SavingsAccountTransactionData;
import org.apache.fineract.integrationtests.client.feign.FeignSavingsTestBase;
import org.apache.fineract.integrationtests.client.feign.modules.SavingsRequestBuilders;
import org.apache.fineract.integrationtests.client.feign.modules.SavingsTestData;
import org.apache.fineract.integrationtests.client.feign.modules.SavingsTestValidators;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.junit.jupiter.api.Test;

public class SavingsAccrualAccountingIntegrationTest extends FeignSavingsTestBase {

    private static final String ACCRUAL_JOB = "Add Accrual Transactions For Savings";
    private static final String SAVINGS_TRANSACTION_ID_PREFIX = "S";
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    private static final String BUSINESS_DATE = "2021-08-12";
    private static final LocalDate TODAY = LocalDate.of(2021, 8, 12);
    private static final int DAYS_TO_SUBTRACT = 10;
    private static final String CLIENT_ACTIVATION_DATE = "01 January 2020";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US);

    private static final String AMOUNT = "10000";
    private static final Double INTEREST_RATE = 10.0;
    private static final Double OVERDRAFT_INTEREST_RATE = 21.0;
    private static final BigDecimal OVERDRAFT_LIMIT = new BigDecimal("10000");

    private static final BigDecimal PERCENT = new BigDecimal("100");
    private static final int DIGITS_AFTER_DECIMAL = 4;

    @Test
    public void testPositiveAccrualPostsCorrectJournalEntries() {
        businessDateHelper.runAt(BUSINESS_DATE, () -> {
            final Account savingsReferenceAccount = accountHelper.createAssetAccount("Savings Reference");
            final Account interestOnSavingsAccount = accountHelper.createExpenseAccount("Interest on Savings (Expense)");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable (Liability)");
            final Account incomeFromFeesAccount = accountHelper.createIncomeAccount("Income from Fees");

            final PostSavingsProductsRequest product = SavingsRequestBuilders
                    .withAccrualAccountingMappings(accrualProduct(INTEREST_RATE), savingsReferenceAccount, interestPayableAccount,
                            incomeFromFeesAccount, interestOnSavingsAccount)
                    .savingsControlAccountId(SavingsRequestBuilders.accountId(savingsControlAccount));

            final Long savingsProductId = savingsProductHelper.createSavingsProduct(product).getResourceId();
            assertNotNull(savingsProductId, "Failed to create savings product.");

            final Long savingsAccountId = createActiveSavingsAccount(savingsProductId);
            deposit(savingsAccountId, AMOUNT, startDateString());

            schedulerHelper.executeAndAwaitJob(ACCRUAL_JOB);

            final List<SavingsAccountTransactionData> accrualTransactions = savingsTransactionHelper
                    .getAccrualTransactions(savingsAccountId);
            assertFalse(accrualTransactions.isEmpty(), "No accrual transactions were found.");

            final List<JournalEntryTransactionItem> journalEntries = journalEntriesOf(accrualTransactions.get(0));
            assertFalse(journalEntries.isEmpty(), "No journal entries found for positive accrual.");
            assertHasEntry(journalEntries, DEBIT, interestOnSavingsAccount,
                    "DEBIT to Interest on Savings (Expense) Account not found for positive accrual.");
            assertHasEntry(journalEntries, CREDIT, interestPayableAccount,
                    "CREDIT to Interest Payable (Liability) Account not found for positive accrual.");

            verifyEveryAccrualIsOneDayOfInterest(accrualTransactions, INTEREST_RATE);
        });
    }

    @Test
    public void testNegativeAccrualPostsCorrectJournalEntries() {
        businessDateHelper.runAt(BUSINESS_DATE, () -> {
            final Account savingsReferenceAccount = accountHelper.createAssetAccount("Savings Reference");
            final Account interestReceivableAccount = accountHelper.createAssetAccount("Interest Receivable (Asset)");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account overdraftInterestIncomeAccount = accountHelper.createIncomeAccount("Overdraft Interest Income");
            final Account interestOnSavingsAccount = accountHelper.createExpenseAccount("Interest on Savings (Expense)");

            final PostSavingsProductsRequest product = SavingsRequestBuilders
                    .withAccrualAccountingMappings(accrualProduct(OVERDRAFT_INTEREST_RATE).allowOverdraft(true), savingsReferenceAccount,
                            savingsControlAccount, overdraftInterestIncomeAccount, interestOnSavingsAccount, interestReceivableAccount)
                    .overdraftLimit(OVERDRAFT_LIMIT)//
                    .nominalAnnualInterestRateOverdraft(BigDecimal.valueOf(OVERDRAFT_INTEREST_RATE));

            final Long savingsProductId = savingsProductHelper.createSavingsProduct(product).getResourceId();
            assertNotNull(savingsProductId, "Savings product with overdraft creation failed.");

            final Long savingsAccountId = createActiveSavingsAccount(savingsProductId);
            withdraw(savingsAccountId, AMOUNT, startDateString());

            schedulerHelper.executeAndAwaitJob(ACCRUAL_JOB);

            final List<SavingsAccountTransactionData> accrualTransactions = savingsTransactionHelper
                    .getAccrualTransactions(savingsAccountId);
            assertFalse(accrualTransactions.isEmpty(), "No accrual transactions were found for overdraft.");

            final List<JournalEntryTransactionItem> journalEntries = journalEntriesOf(accrualTransactions.get(0));
            assertFalse(journalEntries.isEmpty(), "No journal entries found for negative accrual.");
            assertHasEntry(journalEntries, DEBIT, interestReceivableAccount,
                    "DEBIT to Interest Receivable (Asset) Account not found for negative accrual.");
            assertHasEntry(journalEntries, CREDIT, overdraftInterestIncomeAccount,
                    "CREDIT to Overdraft Interest Income Account not found for negative accrual.");

            verifyEveryAccrualIsOneDayOfInterest(accrualTransactions, OVERDRAFT_INTEREST_RATE);
        });
    }

    private void verifyEveryAccrualIsOneDayOfInterest(final List<SavingsAccountTransactionData> accrualTransactions,
            final Double interestRate) {
        final BigDecimal expectedDailyInterest = dailyInterest(interestRate);
        for (SavingsAccountTransactionData accrual : accrualTransactions) {
            SavingsTestValidators.verifyAmount(expectedDailyInterest, accrual.getAmount(), "Verifying the accrual of " + accrual.getDate());
        }
    }

    /** Ordered the way the server does it: the annual rate becomes a daily one before it is applied. */
    private BigDecimal dailyInterest(final Double interestRate) {
        final BigDecimal interestRateAsFraction = BigDecimal.valueOf(interestRate).divide(PERCENT);
        final BigDecimal multiplicand = BigDecimal.ONE
                .divide(BigDecimal.valueOf(SavingsTestData.InterestCalculationDaysInYearType.DAYS_365), MathContext.DECIMAL64);
        final BigDecimal dailyInterestRate = interestRateAsFraction.multiply(multiplicand, MathContext.DECIMAL64);
        return new BigDecimal(AMOUNT).multiply(dailyInterestRate, MathContext.DECIMAL64).setScale(DIGITS_AFTER_DECIMAL,
                RoundingMode.HALF_EVEN);
    }

    private List<JournalEntryTransactionItem> journalEntriesOf(final SavingsAccountTransactionData transaction) {
        return journalEntryHelper.getJournalEntriesByTransactionId(SAVINGS_TRANSACTION_ID_PREFIX + transaction.getId()).getPageItems();
    }

    private void assertHasEntry(final List<JournalEntryTransactionItem> journalEntries, final String entryType, final Account account,
            final String message) {
        assertTrue(journalEntries.stream().anyMatch(entry -> entry.getEntryType() != null
                && entryType.equals(entry.getEntryType().getValue()) && account.getAccountID().longValue() == entry.getGlAccountId()),
                message);
    }

    private Long createActiveSavingsAccount(final Long savingsProductId) {
        final Long clientId = createClient(CLIENT_ACTIVATION_DATE);
        assertNotNull(clientId);

        final String startDateString = startDateString();
        final Long savingsAccountId = submitSavingsApplication(clientId, savingsProductId, startDateString).getSavingsId();
        assertNotNull(savingsAccountId);

        approveSavings(savingsAccountId, startDateString);
        activateSavings(savingsAccountId, startDateString);
        SavingsTestValidators.verifySavingsIsActive(savingsHelper.getSavingsStatus(savingsAccountId));
        return savingsAccountId;
    }

    private String startDateString() {
        return DATE_FORMATTER.format(TODAY.minusDays(DAYS_TO_SUBTRACT));
    }

    private PostSavingsProductsRequest accrualProduct(final Double nominalAnnualInterestRate) {
        return SavingsRequestBuilders
                .savingsProduct(SavingsTestData.InterestCompoundingPeriodType.MONTHLY, SavingsTestData.InterestPostingPeriodType.MONTHLY,
                        SavingsTestData.InterestCalculationType.DAILY_BALANCE)
                .nominalAnnualInterestRate(nominalAnnualInterestRate)//
                .accountingRule(SavingsTestData.AccountingRule.ACCRUAL_PERIODIC);
    }
}
