# Fineract Feign Migration — PR Review Lessons Learned

> Consolidated reference from budaidev, adamsaghy & Aman-Mittal review feedback.  
> Use this as a checklist before submitting any Feign migration or integration test PR.
>
> **#6, #8, #11, #19 and #20 were rewritten after PR #6194** — raw HTTP (`FeignRawHttpHelper`) is no
> longer an accepted fallback for Swagger model gaps. Fix the spec at source.

---

## 1. Assertion Matching: anyMatch + size ≠ exact match

**Reviewer**: budaidev  
**Context**: `verifyJournalEntriesMatch` used `anyMatch` to check each expected entry existed in actuals, plus a size check.

**Anti-pattern**:
```java
// WRONG: size matches + anyMatch can still pass with duplicates/swaps
assertEquals(expectedEntries.length, actualEntries.size());
for (var expected : expectedEntries) {
    assertTrue(actualEntries.stream().anyMatch(a -> matches(a, expected)));
}
```

**Why it fails**:
- If you expect `[A, B]` but get `[A, A]`, `anyMatch(A)` and `anyMatch(B)` both pass on the first A.
- Extra unexpected entries can slip through when duplicates absorb the count.

**Correct pattern** — remove each match from a mutable working copy:
```java
List<ActualEntry> remaining = new ArrayList<>(actualEntries);
for (var expected : expectedEntries) {
    var match = remaining.stream().filter(a -> matches(a, expected)).findFirst();
    assertTrue(match.isPresent(), "Missing: " + expected);
    remaining.remove(match.get());
}
assertTrue(remaining.isEmpty(), "Unexpected extra entries: " + remaining);
```

> **Apply wherever**: any test helper that verifies an unordered collection match.

---

## 2. Null Semantics: "must be null" vs "don't care"

**Reviewer**: budaidev  
**Context**: Transaction portion validation changed `Objects.equals(actual, expected)` to `(expected == null || Objects.equals(actual, expected))`.

**Anti-pattern**:
```java
// WRONG: null now means "skip this check" instead of "assert it's null"
(tr.principalPortion == null || Objects.equals(actual.getPrincipalPortion(), tr.principalPortion))
```

**Why it's dangerous**: Tests that explicitly expect a portion to be `null` (zero/unset) will silently pass even if the server returns a non-null value.

**Correct pattern** — use method overloading:
- **Full Transaction** (with all portions): always assert all fields, including null.
- **Simple Transaction** (amount + type + date only): separate overload that doesn't check portions at all.

> **Apply wherever**: any test validator/data class that uses `null` to represent "field not provided". Keep strict equality for the full check; add a simpler overload when you truly don't care.

---

## 3. No FQN References — Always Use Imports

**Reviewer**: budaidev  
**Context**: Multiple instances of `java.util.Objects.equals(...)`, `org.junit.jupiter.api.Assertions.assertNotNull(...)`, `com.google.gson.JsonParser.parseString(...)`.

**Anti-pattern**:
```java
java.util.Objects.equals(tr.getType().getValue(), type)
org.junit.jupiter.api.Assertions.assertNotNull(repayment);
com.google.gson.JsonParser.parseString(json).getAsJsonObject();
```

**Rule**: Always add an `import` statement and use the simple class name. FQN in code is a code smell.

```java
import java.util.Objects;
import com.google.gson.JsonParser;
// ...
Objects.equals(tr.getType().getValue(), type);
JsonParser.parseString(json).getAsJsonObject();
```

> **Apply everywhere**: search for `\b[a-z]+\.[a-z]+\.[A-Z]` patterns in test code before submitting.

---

## 4. Method Placement: Base Class vs Specific Test

**Reviewer**: budaidev, adamsaghy  
**Context**: Raw-JSON `updateLoanProduct` was placed in `FeignLoanTestBase`, pulling REST-assured into every subclass.

**Rule**:
- If **only one test class** needs a method → keep it `private` in that test class.
- If **multiple tests** share it → promote to base class or a helper.
- **Never pollute** the base class with single-use or workaround methods.

**Corollary (budaidev)**: Loan product template methods (e.g., `create4IProgressive()`) belong in `LoanProductTemplates`, not in `FeignLoanTestBase`.

> **Apply whenever** adding new helper methods. Ask: "Does more than one test need this?"

---

## 5. Avoid Magic Numbers and Strings

**Reviewer**: budaidev  
**Context**: `officeId(1L)`, `legalFormId(1L)`, hardcoded dates like `"04 March 2011"`.

**Anti-pattern**:
```java
new PostClientsRequest().officeId(1L).legalFormId(1L)
createClient("04 March 2011");
```

**Rule**: Extract to named constants or delegate to builder methods that centralize defaults:
```java
public static final Long DEFAULT_OFFICE_ID = 1L;
// or use ClientRequestBuilders which already centralizes this
```

> **Apply**: any literal number/string that represents a business concept (office, legal form, date, currency code).

---

## 6. No REST-Assured *and No Raw HTTP* — Fix the Spec at Source

**Reviewer**: adamsaghy
**Context**: originally, using REST-assured as a workaround for Swagger model gaps. Superseded on PR #6194 (FINERACT-2718), where adamsaghy requested changes with: *"Why the `FeignRawHttpHelper` usages? We would like to avoid serializing back and forth json objects..."*

**Rule**: neither REST-assured **nor** `FeignRawHttpHelper` belongs in Feign-based test code. Hand-built JSON strings are the exact thing the migration exists to remove — swapping REST-assured for `HttpURLConnection` keeps the round-trip and only hides it. `FeignRawHttpHelper` is legacy on `develop`; do not add usages.

**Escalation order when the typed client can't express what you need**:
1. **Add the missing field to the server `*ApiResourceSwagger` DTO and regenerate** — this is the answer in the overwhelming majority of cases, including command bodies and `changes` responses. See #8.
2. **Fix the endpoint's `@ApiResponse` schema** when the declared response type is simply wrong. See #28.
3. **Hand-written typed Feign interface** — only when the spec genuinely cannot express the shape (see #28's exception), binding to *generated* models so the call is still typed.
4. **Raw JSON** — not an option. If you believe it is, you have found case 1, 2 or 3.

> **Apply**: when you reach for `Utils.performServerPost`, a REST-assured `RequestSpecification`, or `FeignRawHttpHelper` in a Feign-based test.

---

## 7. Return Full Response Objects, Not Just IDs

**Reviewer**: adamsaghy  
**Context**: `FeignSavingsHelper.submitApplication()` and `FeignSavingsProductHelper.createSavingsProduct()` returned only the resource ID.

**Anti-pattern**:
```java
public Long submitApplication(PostSavingsAccountsRequest request) {
    PostSavingsAccountsResponse response = ok(...);
    return response.getSavingsId();
}
```

**Correct pattern**:
```java
public PostSavingsAccountsResponse submitApplication(PostSavingsAccountsRequest request) {
    return ok(...);
}
```

**Why**: The response is already fetched. Returning it improves reusability — callers may need `externalId`, `status`, etc. without making another API call.

> **Apply**: all Feign helper methods that create/submit resources. Return the full response; let the caller extract what it needs.

---

## 8. Swagger Model Gaps → Silent Data Loss

**Reviewer**: (discovered via failing test)  
**Context**: `PostLoanProductsRequest` doesn't include floating rate fields (`floatingRatesId`, `interestRateDifferential`, `minDifferentialLendingRate`, `defaultDifferentialLendingRate`, `maxDifferentialLendingRate`, `isFloatingInterestRateCalculationAllowed`).

**Anti-pattern**:
```java
HashMap<String, Object> map = new LoanProductTestBuilder()...build(null, null);
map.put("floatingRatesId", floatingRateId);          // present in JSON
map.put("isFloatingInterestRateCalculationAllowed", true);
return getLoanProductId(Utils.convertToJson(map));    // deserializes to PostLoanProductsRequest → fields DROPPED
```

**What happens**: `ObjectMapperFactory.getShared()` ignores unknown properties by default. Fields not in the model are silently discarded. The server then returns 400 with "parameter is mandatory" errors.

**Fix — add the field to the spec**: The generated client models (`fineract-client{,-feign}/build/generated/.../models/*.java`) are produced at build time from the server's `*ApiResourceSwagger` DTOs. To add a field:
1. Add it to the matching inner class in the server Swagger DTO, e.g. `SavingsProductsApiResourceSwagger.PostSavingsProductsRequest`:
   ```java
   @Schema(example = "10000.0")
   public BigDecimal minRequiredOpeningBalance;   // was a gap: only enforceMinRequiredBalance existed
   ```
   Use the **exact** server JSON param name (confirm via `SavingsApiConstants.minRequiredOpeningBalanceParamName` / the validator), and a Java type that maps cleanly (`BigDecimal` → OpenAPI `number`).
2. Rebuild so `:fineract-provider:resolve` regenerates the spec and the SDK regenerates the model. The generated sources are build artifacts (git-ignored) — you only commit the Swagger DTO change + the test that now uses the typed setter.
3. In the test, use the typed builder: `SavingsRequestBuilders.defaultSavingsProduct().minRequiredOpeningBalance(new BigDecimal("10000.0"))`.

**Regen gotcha (constrained env)**: `:fineract-client-feign:buildJavaSdk` runs the OpenAPI generator in a worker JVM. With `org.gradle.parallel=true` both SDKs generate at once and the worker can die with `Java heap space` (especially with the live server also resident). Re-run the generation **serialized**: `./gradlew :fineract-client-feign:buildJavaSdk --no-parallel --max-workers=1`. A long-lived daemon that has already done other work fails the same way even at `-Xmx12g` — `./gradlew --stop` and retry on a fresh daemon also clears it.

**"The gap is too broad to fix at source" is almost always wrong** (PR #6194). An earlier version of this entry said to keep raw HTTP when the gap "spans many fields across several endpoints/models" — group/center commands, `changes` responses, GLIM apply/approve. That carve-out did not survive review: fixing all of it at source took **4 Swagger files, ~10 DTOs and one regen**, and removed every raw call in the PR. Breadth is not a reason to keep raw JSON; it just means more `@Schema` fields in one commit.

**Command DTOs and `changes` responses count too.** The shared `Post{Groups,Centers,Clients}...Request`/`Response` models are usually near-empty in the spec (`resourceId` only), which reads like "the API can't do this" — it only means nobody documented it. Add the command fields to the request DTO and a nested `...Changes` class to the response:
```java
static final class PostGroupsGroupIdChanges {
    @Schema(example = "1")
    public Long staffId;
}
public PostGroupsGroupIdChanges changes;
```
Then `handleCommandsGroup(...).getChanges().getStaffId()` replaces parsing `changes` out of a JSON string.

**Confirm the shape against the server, not your assumption.** `changes.groupMembers` is a `List<String>` (ids as strings), because `Group#associateGroups` returns `List<String>` — declaring `List<Long>` would document a lie. Read the write-platform service before choosing the type.

> **Apply**: whenever a field you need is absent from a generated Feign model. Add it to the `*ApiResourceSwagger` DTO — for single fields, whole command bodies, and `changes` responses alike.

---

## 9. Verify Side Effects — Don't Just Fire and Forget

**Reviewer**: budaidev  
**Context**: `deleteClient(clientId)` was called but the test never verified the client was actually deleted.

**Anti-pattern**:
```java
clientHelper.deleteClient(pending.getClientId());
// test ends — no verification
```

**Correct pattern**:
```java
clientHelper.deleteClient(pending.getClientId());
// Verify: expect 404 on subsequent fetch
assertThrows(CallFailedRuntimeException.class,
    () -> clientHelper.getClient(pending.getClientId()));
```

> **Apply**: any destructive operation (delete, reject, close). Always verify the expected post-condition.

---

## 10. Assertions Should Be Precise

**Reviewer**: budaidev  
**Context**: Search test only checked `assertFalse(results.isEmpty())` without verifying the returned client matched.

**Anti-pattern**:
```java
PageClientSearchData results = clientHelper.searchClients(name);
assertFalse(results.getTotalFilteredRecords() == 0); // too loose
```

**Correct pattern**:
```java
PageClientSearchData results = clientHelper.searchClients(name);
assertTrue(results.getPageItems().stream()
    .anyMatch(c -> c.getId().equals(expectedClientId)));
```

> **Apply**: any test that creates a resource and then searches/queries for it. Assert on the specific resource identity, not just non-emptiness.

---

## 11. Swagger Model Mismatches for Command-Specific Fields

**Reviewer**: budaidev, adamsaghy  
**Context**: `PostSavingsAccountsAccountIdRequest` doesn't have `rejectedOnDate` — test used `closedOnDate` as workaround, but the server actually validates `rejectedOnDate`.

**Key learning**: The server-side validator explicitly extracts field names from the JSON. Using a different field name (even if the model compiles) means:
- The intended field is **never sent**
- The test may silently pass if it's in a `try/catch` cleanup block
- The feature is **not actually being tested**

**Rule**: Trace through the server-side validator to confirm which JSON field names are expected, then **add those exact names to the shared command DTO** (#8) so the typed call sends them. Do not substitute a different field, and do not fall back to raw JSON (#6).

> **Apply**: any command-specific endpoint (`?command=reject`, `?command=undoRejection`, etc.) where the request model is shared across multiple commands. A shared DTO accumulating fields from several commands is expected and fine — annotate each with which command uses it.

---

## 12. Naming Should Reflect Domain Semantics

**Reviewer**: budaidev  
**Context**: `createClientPending()` — name questioned because it wasn't clear what "pending" meant.

**Rule**: When naming methods, ensure the name clearly communicates:
- **What** it creates (client)
- **In what state** (pending = `active=false`, not yet activated)
- **Why it returns what it returns** (full response vs just ID)

Document non-obvious naming in Javadoc:
```java
/**
 * Creates a client in {@code pending} status (active=false).
 * Returns full response for access to resourceExternalId.
 */
public PostClientsResponse createClientPending(String date) { ... }
```

---

## 13. Keep Builder Methods Separate Even If Bodies Look Identical

**Reviewer**: budaidev  
**Context**: `undoRejectClient()` and `undoWithdrawnClient()` have identical request bodies but map to different server commands.

**Rule**: If two operations are semantically different (different `?command=` values), keep separate builder methods even if the request body is the same. This makes test code self-documenting about which state transition is being exercised.

---

## 14. Remove Unused Aliases and Dead Code

**Reviewer**: budaidev  
**Context**: `DATE_FORMAT = DATETIME_PATTERN` — an unused alias in `FeignTestConstants`.

**Rule**: Don't create aliases "just in case". If a field isn't used, remove it. Each domain's request builder should reference the constant directly.

---

## 15. Use Enums over Magic Strings/Numbers

**Reviewer**: Aman-Mittal, adamsaghy
**Context**: Reviewers flagged usages of `"CREDIT"`, `"DEBIT"`, numeric months, and `"BUSINESS_DATE"` strings.

**Anti-pattern**:
```java
journalEntry(250.0, account, "CREDIT");
LocalDate.of(2023, 3, 1);
updateBusinessDate("BUSINESS_DATE", date);
```

**Correct pattern**:
```java
journalEntry(250.0, account, JournalEntry.TransactionType.CREDIT.name());
LocalDate.of(2023, Month.MARCH, 1);
BusinessDateHelper.updateBusinessDate(BusinessDateType.BUSINESS_DATE, date);
```

> **Apply**: Always use domain-specific enums (`java.time.Month`, `BusinessDateType`, `JournalEntry.TransactionType`) instead of their underlying string or numeric representations.

---

## 16. Avoid Raw Types and Unchecked Casts in Deserialization

**Reviewer**: Aman-Mittal
**Context**: `FeignLoanTestBase` used `HashMap` and raw `(List<Map<String, Object>>)` casts which resulted in `@SuppressWarnings("unchecked")`.

**Anti-pattern**:
```java
@SuppressWarnings("unchecked")
List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
```

**Correct pattern**: Use Gson's `TypeToken` or Jackson's `TypeReference` to deserialize safely without warnings.
```java
List<Map<String, Object>> items = gson.fromJson(json, new TypeToken<List<Map<String, Object>>>(){}.getType());
```

> **Apply**: When extracting complex nested structures from JSON, avoid raw casts. If an `@SuppressWarnings("unchecked")` is absolutely necessary (e.g. dealing with generic libraries), limit its scope strictly to the variable assignment.

---

## 17. Name Variables by Purpose, Not Type

**Reviewer**: adamsaghy
**Context**: `CommandStrategyUtilsTest` used a variable named `bigDecimal`.

**Anti-pattern**:
```java
if (expectedValue instanceof BigDecimal bigDecimal) { ... }
```

**Correct pattern**:
```java
if (expectedValue instanceof BigDecimal expectedValueAsBigDecimal) { ... }
```

> **Apply**: "bigDecimal is the type, not the purpose." Always name variables based on what they represent in the domain or logic.

---

## 18. Fix Lingering Patterns Holistically

**Context**: Reviewers often flag an anti-pattern (like numeric months) in just *one* file as an example.
**Rule**: If a reviewer flags a pattern in one file, you must scan the *entire PR* for that pattern and fix it everywhere, especially in files you actively migrated or touched. Leaving the same anti-pattern in other files in the same PR leads to fragmented code quality and follow-up review comments.

---

## 19. Don't Substitute a Semantically-Different Field During Migration

**Reviewer**: budaidev
**Context**: A per-period `principalDisbursed` JSON read was migrated to `period.getPrincipalLoanBalanceOutstanding()`.

**Anti-pattern**:
```java
// Original REST: reads the tranche's disbursed principal
BigDecimal principalDisbursed = parse(loanSchedule.get(i).get("principalDisbursed"));
// Migrated: WRONG — this is the running loan balance, not the disbursed amount
BigDecimal principalDisbursed = loanSchedule.get(i).getPrincipalLoanBalanceOutstanding();
```

**Why it's dangerous**: `principalLoanBalanceOutstanding` is a *running cumulative balance*; summing it across disbursement rows produces a completely different number than the sum of per-tranche disbursals. The test may only pass because someone then *nudged the expected value* (see #21).

**Fix**: sum the per-row `principalDisbursed` across disbursement rows (`period` absent/null). It is **not** on the generated `GetLoansLoanIdRepaymentPeriod` model — that is a Swagger gap to close (#8), not a licence to read the loan JSON raw (#6).

**Verified caveat — the obvious aggregate is NOT equivalent**: `loanDetails.getSummary().getPrincipalDisbursed()` (the reviewer's suggestion) was tried and **fails against the live server** for the 8-tranche, no-repayment case (returns something ≠ 255), even though it *happens* to match for the small reopened-loan cases. Don't trust a plausible-looking aggregate — run it. The only reliable source here is the per-row `principalDisbursed`.

> **Apply**: when a JSON `map.get("x")` becomes a typed getter, confirm the getter is the *same* business quantity — matching names (`principalDisbursed` vs `principalLoanBalanceOutstanding`) is not enough — and **verify against a running server**, because a summary aggregate is not guaranteed to equal the per-row sum.

---

## 20. Don't Silently Weaken a Verification When the Model Lacks a Field

**Reviewer**: budaidev
**Context**: `verifyTransactionIsAccountTransfer` stopped checking the `transfer` sub-object (its `transferAmount`/`transferDate`) because the generated Feign `GetLoansLoanIdTransactions` model has no `transfer` field — so it just re-asserted the amount it had already matched on.

**Anti-pattern**: A method whose *name* promises a check (`...IsAccountTransfer`) that its body no longer performs. The behaviour the test exists to prove is no longer proven.

**Fix**: add the missing field to the `*ApiResourceSwagger` DTO and assert on the typed getter (#8). The same applied to the group/center migration: `GetGroupsGroupIdResponse` had no `active`/`clientMembers` and `GetCentersCenterIdResponse` no `externalId`/`staffId`/`groupMembers`, which had been worked around with raw reads — adding them restored typed assertions and let `CenterDomain` (a REST-assured-era POJO) be dropped from those tests entirely.

> **Apply**: if a migration drops an assertion "because the model doesn't have it", that's a Swagger gap to fix at source, never a reason to lower the bar — and never a reason to keep a raw-JSON reader around (#6). The method name is the contract.

---

## 21. A Changed Expected Value Is a Red Flag — but Let the Live Server Be the Judge, Not Your Intuition

**Reviewer**: budaidev
**Context**: `principalDue`/`interestDue` expectations shifted by one cent (1177.12/152.88 → 1177.13/152.87) during a REST→Feign migration. Reviewer flagged it as "unusual to change expected values in a refactor."

**What actually happened**: The reviewer was **right to question it**, but the migrated value (1177.13/152.87) turned out to be **correct** — the live server returns `principalDue: 1177.130000` in the Feign flow. Blindly "restoring" the original 1177.12 (on the theory that a refactor can't change server math) **broke the test**, confirmed only by running it against a live backend.

**Why the original differed**: The pre-migration test asserted 1177.12; the Feign path yields 1177.13. The refactor legitimately surfaced a different principal/interest rounding split (both still sum to the EMI 1330.00) — likely a subtle difference in how the migrated repayment/schedule retrieval exercises the server, not a test-authoring error.

**Rule**: Treat a changed expected value as a **red flag to investigate**, not as something to reflexively revert *or* accept. The tie-breaker is **running the test against a live server** — never your intuition about what "should" be invariant. Then explain the confirmed value to the reviewer rather than silently changing code.

> **Apply**: diff every changed numeric literal (`git show <commit> | grep -E "^[+-].*(assertEquals|compareTo).*[0-9]\.[0-9]"`), then **run each touched test against a running backend** (`./gradlew :integration-tests:test -PcargoDisabled --tests ...`) before concluding which value is correct.

---

## 22. Assert the Specific Error Status, Not Just That It Throws

**Reviewer**: budaidev
**Context**: `assertThrows(CallFailedRuntimeException.class, ...)` replaced an original `responseSpecErr403`, dropping the status assertion.

**Anti-pattern**:
```java
assertThrows(CallFailedRuntimeException.class, () -> reverseLoanTransaction(loanId, id, date));
```

**Correct pattern** — capture and assert the status (mirroring the existing 500/503 checks in the same file):
```java
CallFailedRuntimeException ex = assertThrows(CallFailedRuntimeException.class,
        () -> reverseLoanTransaction(loanId, id, date));
assertEquals(403, ex.getStatus());
```

**Why**: `assertThrows` alone passes on *any* failure — a 400, 404, or 500 would mask the real behaviour. The original response spec pinned the exact code; preserve that. `CallFailedRuntimeException.getStatus()` exposes it.

> **Apply**: everywhere a `responseSpecErrNNN` was migrated to `assertThrows`. Fix holistically (#18): in the same file two reverses + one adjust all expected 403.

---

## 23. Use the Feign Client, Not the Legacy Retrofit `Calls`/`.legacy`

**Reviewer**: budaidev
**Context**: A Feign helper called `Calls.ok(FineractClientHelper.getFineractClient().legacy.getAdvancedPaymentAllocationRulesOfLoan(loanId))` — the old Retrofit client — inside the new Feign infrastructure.

**Anti-pattern**:
```java
return Calls.ok(FineractClientHelper.getFineractClient().legacy.getAdvancedPaymentAllocationRulesOfLoan(loanId));
```

**Correct pattern** — the endpoint already exists on the Feign `DefaultApi`:
```java
return ok(() -> fineractClient.defaultApi().getAdvancedPaymentAllocationRulesOfLoan(loanId));
```

**Why**: The whole point of the migration is to remove the Retrofit `client.util.Calls` / `.legacy` client. Reaching back into it defeats it and re-introduces the dependency. After removing the last use, also drop the now-unused `Calls` / `FineractClientHelper` imports.

> **Apply**: `grep -rn "\.legacy\b\|import org.apache.fineract.client.util.Calls;" <feign test tree>` — there should be zero hits. Prefer `fineractClient.xxxApi()` + the helper's own `ok(...)`/`fail(...)`.

---

## 24. Compile + Spotless Is NOT Verification — Run the Touched Tests Against a Live Server

**Context**: Two review fixes (#19 getSummary, #21 the 1177.12 "restore") compiled cleanly, passed spotless, and looked correct by inspection — yet **both failed at runtime**. Only `./gradlew :integration-tests:test -PcargoDisabled --tests ...` against a running backend caught them.

**Rule**: For integration-test changes, "green" means the **test executed and passed against a live server**, not that it compiled. Before claiming a fix is done:
1. Ensure a backend is up (`curl -sk https://localhost:8443/fineract-provider/actuator/health` → 200).
2. Run every touched test class/method with `-PcargoDisabled` (skips Cargo's own Tomcat, runs against the already-running server).
3. Read the actual asserted values from the failure report — don't guess.

**Never state a fix is verified on the basis of compile + spotless alone.** Say explicitly what you did and did not run.

> **Apply**: any change to `integration-tests`. `-PcargoDisabled` + `--tests <FQCN>[.<method>]` is the fast inner loop; parameterized tests run all parameters.

---

## 25. The Strict Feign Client Surfaces Latent Future-Date Test Bugs

**Context**: `LoanChargesMultipleDebitAccountsTest.testMissingGLAccountMappingHandling` ran `runAt("20 February 2023", …)` but made a repayment dated `"25 February 2023"` — five days past the business date. The old RestAssured path let this through (test passed vacuously); the Feign client correctly gets a `403 error.msg.loan.transaction.cannot.be.a.future.date`.

**Why it matters**: A migrated test can fail **only in the full-class run vs. isolation, or vice-versa**, and look like flakiness. Verify the pre-migration behaviour on a live server (`git checkout` the original, run the one method) before concluding. Here the original passed in isolation and the migrated failed in isolation — a real behaviour change, not flakiness.

**Fix**: Align the transaction date with the `runAt` business date (every other method in that file repaid on the business date; the `25 February` was a copy-paste inconsistency). Do **not** loosen the Feign helper to swallow the error — the strict throw is correct (see #22).

> **Apply**: when a migrated test throws a `cannot.be.a.future.date` 403, check the transaction date against the enclosing `runAt`/business date. It's a latent bug the stricter client exposed, not a migration regression to paper over.

---

## 26. "Remove the Shadowing Field" Is Only Valid When the Field Is Actually Dead — Check Static-vs-Instance and Used-vs-Unused

**Context**: A review flagged child-class fields (`private ClientHelper clientHelper`, `private AccountHelper accountHelper`) that shadow the parent's Feign fields of the same name, recommending "remove them all — NPE risk." Removing them wholesale is **not** always safe.

**Two failure modes the blanket rule misses**:
1. **Dead static-call qualifier** — `clientHelper.createClient(ClientHelper.defaultClientCreationRequest())` compiles and runs even when the local `clientHelper` is `null`, because `ClientHelper.createClient(PostClientsRequest)` is a **static** method (Java resolves a static call through a null instance reference without dereferencing it). The field is genuinely dead; removing it makes the reference resolve to the parent's Feign `clientHelper`, whose `createClient(PostClientsRequest)` returns the same `PostClientsResponse`. **Safe to remove** — and it actually completes the migration to Feign.
2. **Genuinely-used instance field** — `accountHelper.createExpenseAccount()` (an *instance* REST-assured call returning a REST-assured `Account`). The parent's `FeignAccountHelper` has no such method / different return type, so removing the field **breaks compilation**. **Do not remove**; keeping it (or renaming to de-shadow) is correct. Migrating it to Feign account creation is a separate, behaviour-affecting change (journal-entry assertions depend on those account objects) — out of scope for a review fix.

**How to decide before deleting a shadowing field**: grep every usage. If the only usages are `field.STATICMETHOD(...)` (verify the method is `static`) or there are no usages at all → dead, remove. If any usage is an instance method the parent type doesn't expose → keep. A field being `null` at runtime is *not* proof it's safe to delete.

> **Apply**: whenever a reviewer says "remove the shadowing/duplicate field," classify each usage (static call, unused, or real instance call) first. Compile **and** run after removal — resolution silently rebinds the name to the inherited field.

---

## 27. Folding Review Fixes Into Their Originating Commits — Use the Final-Tree Diff as the Correctness Oracle, and Don't Assume Per-Commit Compilability

**Context**: The task was to squash ~12 review fixes into the specific PR commits that introduced each piece of code (not a trailing "review fixes" commit), keeping the history clean.

**Workflow that worked**:
1. Apply *all* fixes to the working tree first; get them green (compile + spotless + touched tests on a live server) **before** touching history.
2. Snapshot the fully-fixed tree: `git add -A && T0=$(git write-tree)` then `git reset -q`.
3. Route each fix to its introducing commit found via `git log -S'<changed text>' -- <file>` (whole files → `git add <file>`; a file whose two edits belong to two different commits → split by hunk with `git apply --cached <hunk>.patch`). Create one `git commit --fixup=<target>` per commit.
4. `GIT_SEQUENCE_EDITOR=true GIT_EDITOR=true git rebase -i --autosquash <base>`.
5. **Oracle**: `git diff $T0 HEAD -- '*.java'` must be empty. If it is, the rebased branch is byte-identical to the tree you already tested — you do **not** need to re-run the whole suite.

**Two traps**:
- **Fixups computed against HEAD conflict when folded into an *earlier* commit that a *later* commit also modified.** A field removed by your fixup may be *re-added* by a later commit's original diff → merge conflict during replay. Resolve to the intended final state; the `$T0` oracle confirms you got it right. (Tip: target the *latest* PR commit that touches a file, not the semantically-oldest, to avoid this.)
- **Intermediate commits of an incremental migration PR may not each compile.** A `git rebase --exec './gradlew … compileTestJava' <base>` can fail on an early commit with errors (`cannot find symbol CallFailedRuntimeException`, …) that are **unrelated to your fold** — the PR was authored so only the final state compiles. Don't try to "fix" it by rewriting all commits; confirm the failing symbols are untouched by your change (pre-existing), abort the exec-rebase, and restore the verified state (tag it first).

> **Apply**: any "squash the review fixes into their own commits" request. Tag the good state before an exec-rebase (`git tag verified-distribution`), and trust `git diff <snapshot> HEAD` over re-running tests.

---

## 28. Fix Server OpenAPI Schema (@ApiResponse) Instead of Creating Workaround Feign Interfaces

**Reviewer**: adamsaghy  
**Context**: An endpoint (`retrieveTransactionTemplate`) returned `LoanTransactionData` on the wire, but its Swagger/OpenAPI `@ApiResponse` declared `GetLoansLoanIdTransactionsTemplateResponse`. Previously, a custom interface (`InternalLoanReAgeApi`) was created as a workaround.

**Anti-pattern**:
```java
// WRONG: Creating custom client interfaces to bypass incorrect OpenAPI return schemas
public interface InternalLoanReAgeApi { ... }
```

**Correct pattern**:
Fix the `@ApiResponse` schema on the server resource implementation (`*ApiResource.java`) directly:
```java
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = LoanTransactionData.class)))
})
public String retrieveTransactionTemplate(...)
```
Then use the standard generated Feign SDK client.

**Note on CI Breaking API checks**: Fixing an `@ApiResponse` schema will trigger automated OpenAPI backward-compatibility alerts (`BREAKING_CHECK_OUTCOME: failure`). Explain in the PR that runtime wire format is unchanged and the OpenAPI spec is being aligned with actual server return types.

**The one legitimate exception — an operation with two response shapes.** `GET /centers` and `GET /groups` serialize a bare JSON array unless `paged=true`, in which case they serialize `{totalFilteredRecords, pageItems}`. One `@ApiResponse` cannot describe both, so "fix the schema" has no correct answer here: declaring the array makes the paged case wrong and vice versa. Only then write a small Feign interface — and bind it to the **generated** models so the call is still typed end to end:
```java
@RequestLine("GET /v1/centers?limit=-1")
List<GetCentersPageItems> listCenters();
```
That is categorically different from `InternalLoanReAgeApi`, which existed to dodge a schema that was simply wrong. Javadoc *why* the interface exists, or someone will "simplify" it back to the generated client (see #33).

---

## 29. Return Strongly-Typed Generated Response Models Instead of Untyped HashMap Wrappers

**Reviewer**: adamsaghy  
**Context**: Test builder helpers (`disburseLoanAsMap`, `getLoanIdFromApplication`) wrapped Feign responses in `HashMap<String, Object>` to mimic legacy REST-assured maps.

**Anti-pattern**:
```java
protected HashMap<String, Object> disburseLoanAsMap(...) {
    PostLoansLoanIdResponse response = loanHelper.disburseLoan(...);
    HashMap<String, Object> result = new HashMap<>();
    result.put("subResourceExternalId", response.getSubResourceExternalId());
    return result;
}
```

**Correct pattern**:
Return strongly-typed generated response models directly (`PostLoansResponse`, `PostLoansLoanIdResponse`). Callers should use typed getters (`response.getSubResourceExternalId()`).

---

## 30. Compile ALL Consuming Modules — Not Just the One You Changed

**Context**: After reverting the `/transactions/template` endpoint's `@ApiResponse` schema from `LoanTransactionData` back to `GetLoansLoanIdTransactionsTemplateResponse`, only `:integration-tests:compileTestJava` was run to verify. It passed — but `fineract-e2e-tests-core` (a separate Gradle module) also consumed the generated SDK's return type. That module's `LoanRepaymentStepDef.java` used `LoanTransactionData response = ok(...)`, which now fails because the SDK returns `GetLoansLoanIdTransactionsTemplateResponse`. The build was declared "green" when it wasn't.

**Anti-pattern**:
```bash
# WRONG: only compiles the module you touched
./gradlew :integration-tests:compileTestJava  # ✅ — but misses fineract-e2e-tests-core
```

**Correct pattern** — compile every module that could consume the changed SDK:
```bash
# Compile all test modules that use the generated Feign SDK
./gradlew :integration-tests:compileTestJava :fineract-e2e-tests-core:compileTestJava :fineract-e2e-tests-runner:compileTestJava
```

**Discovery technique**: When you change a Swagger DTO or `@ApiResponse` schema, grep the entire repo for the old type name:
```bash
grep -rn 'LoanTransactionData' --include='*.java' fineract-e2e-tests-core/ integration-tests/ fineract-client*/ 
```

> **Apply**: any Swagger schema change, `@ApiResponse` fix, or SDK model regeneration. The blast radius is **every module that imports from `org.apache.fineract.client.models.*`**, not just `integration-tests`.

---

## 31. After Rebase, Diff Against Develop to Detect Stray Scope Creep Baked Into Committed History

**Context**: After a clean rebase of 14 commits onto `develop`, plus re-applying comply changes (stash pop), `git diff HEAD` showed 18 changed files. Three of those were **not** part of the intended comply work — they were baked into s3's committed history from earlier development:
1. `SavingsProductsApiResourceSwagger.java` (+`minRequiredOpeningBalance`) — a PROD `src/main` change from commit `bf1ed56bc1`
2. `CommandStrategyUtilsTest.java` (switch-expression→switch-statement revert + variable rename revert) — from `d9c00bece6`
3. `LoanTest.java` (adds `loanTransaction4` to a list) — from `d9c00bece6`/`78c408ade0`

These are **legitimate parts of the PR branch**, not bugs. But `git diff HEAD` alone doesn't distinguish "intended comply changes" from "things the committed branch already changed vs develop." Reviewer objection context (adamsaghy's "no PROD changes") makes this critical.

**Discovery technique**:
```bash
# Diff the worktree against develop (not HEAD) to see the TOTAL PR footprint
git diff develop -- '*.java' --stat
# Then diff each file that surprises you:
git log --oneline --all -- <surprising-file>
```

**Rule**: After any rebase + stash pop, always run `git diff develop` (not just `git diff HEAD`) to see the full PR footprint a reviewer will see. Classify every changed file as "intended by comply" or "baked into branch history." If a baked-in file contradicts reviewer feedback (e.g., PROD changes the reviewer said to remove), you need to revert it in the worktree before amending.

> **Apply**: every post-rebase verification. The diff against `develop` is what the reviewer sees; the diff against `HEAD` only shows your uncommitted layer.

---

## 32. Schema Reverts Have Ripple Effects on SDK Consumers — Check the Generated SDK's Return Type AND Every Caller

**Context**: Reverting `@ApiResponse(schema = LoanTransactionData.class)` back to `GetLoansLoanIdTransactionsTemplateResponse.class` changed the generated Feign SDK's return type for `retrieveTemplateLoanTransaction()`. The s3 branch had earlier changed an e2e test file (`LoanRepaymentStepDef.java`) to use `LoanTransactionData response = ok(...)` matching the s3 schema. After the revert, this became a type mismatch — but the file was outside the module being compiled.

**Two-part check**:
1. **SDK return type**: Verify the on-disk generated SDK (`.../build/generated/.../api/LoanTransactions*.java`) actually reflects the reverted `@ApiResponse`. If not, regenerate.
2. **All callers**: `grep -rn 'retrieveTemplateLoanTransaction\|LoanTransactionData' --include='*.java'` across the **entire repo**, not just `integration-tests/`.

**Anti-pattern**: Assuming the only callers are in the module you're working on. SDK-consuming modules include `fineract-e2e-tests-core`, `fineract-e2e-tests-runner`, `integration-tests`, and potentially any test that uses `fineractClient.*Api()`.

> **Apply**: any `@ApiResponse` schema change or Swagger DTO modification. The ripple goes: Swagger DTO → generated OpenAPI spec → generated SDK models/APIs → **every module importing them**.

---

## 33. `paged=true` Is Not a Free Substitute for the Non-Paged Listing

**Context**: `retrieveOrphanGroups` needed a typed result. The non-paged `GET /groups?orphansOnly=true` answers a bare array the generated client can't decode, so it was switched to `paged=true` to get a typed `GetGroupsResponse`. `testCentersOrphanGroups` then failed — after associating every group of an office to a center, it still saw orphans.

**Cause**: `GroupsApiResource#retrieveAll` builds the same `SearchParameters` for both branches, but only the non-paged `retrieveAll` honours `orphansOnly`; `retrievePagedAll` ignores it. Reproduced against a live server:
```
GET /groups?officeId=26&orphansOnly=true             -> []
GET /groups?officeId=26&orphansOnly=true&paged=true  -> both groups, centerId populated
```

**Why it's nasty**: the two calls differ only by a query param that reads like pure presentation, so the substitution looks free. It silently turns "orphan groups" into "all groups" — here it failed loudly, but the same swap in an assertion expecting a *non-empty* result would have passed vacuously forever.

**Rule**: `paged` is not a presentation-only flag. Before switching a query to the paged variant for the sake of a typed model, check that the read-platform service's paged path applies the same filters — and if it doesn't, keep the non-paged call and type it another way (#28's exception). Leave a comment saying why, since the paged form looks like the obvious cleanup.

> **Apply**: any time a query gains or loses `paged=true` during migration, and any time a filter flag (`orphansOnly`, `underHierarchy`, …) is combined with paging.

---

## 34. Generated `Set` Collections Have No Order — Don't Compare Two Listings Element-Wise

**Context**: `testListCenters` asserts the paged and non-paged listings agree. Migrated to `assertEquals(paginatedList, list)` on `List<GetCentersPageItems>`, it failed with the same centers in different orders.

**Cause**: neither endpoint has an `ORDER BY` unless `orderBy`/`sortOrder` are passed, so **the server's row order is not contractual** — it's whatever the query plan yields, and it differs between two calls made seconds apart. Two `curl`s agreeing is not evidence; the failure output showed *both* listings in non-ascending, mutually-different orders while a manual `curl` of the same two endpoints had returned ascending for both.

**A tempting wrong diagnosis**: "`pageItems` is a `Set`, so Jackson dropped the order." Worth checking, but it wasn't the cause here — the *non-paged* side arrives as a plain `List` and was equally unordered, which pins the problem on the server. (For the record, openapi-generator emits `new LinkedHashSet<>()` and Jackson's default concrete type for `Set` is also `LinkedHashSet`, so wire order does survive.) Don't stop at the first plausible explanation when a second one is provable.

**Fix** — compare on identity, not position:
```java
// Neither listing declares an order, so compare them by id.
assertEquals(byId(paginatedList), byId(list));
```

**Wider point**: the pre-migration test used `assertArrayEquals` over `CenterDomain` and passed, so this was latent order-dependence that the migration merely made deterministic. A pre-existing green test is not proof the assertion was sound.

> **Apply**: any assertion over a listing endpoint that wasn't given an explicit sort. Related: #1 (exact matching for unordered collections).

---

## 35. ALWAYS Pass `-PcargoDisabled` — Every Test Invocation, Without Exception

**The rule**: any Gradle command that can reach `:integration-tests:test` gets `-PcargoDisabled`. Not "usually". Always.

**What happens without it** (`integration-tests/build.gradle:147-157`):

```groovy
tasks.named('test').configure {
    if (!project.hasProperty('cargoDisabled')) {
        dependsOn cargoStartLocal, waitForFineract   // boots its OWN Tomcat
        finalizedBy cargoStopLocal
    }
}
```

The task deploys the war to a Cargo-managed Tomcat, polls `https://localhost:8443/.../actuator/health` until it answers, then runs **the entire integration suite** — hundreds of tests, hours of wall time — and tears the server down. There is no "just the classes I touched" about it.

**The trap is `build`, not `test`.** It is easy to remember the flag on `./gradlew :integration-tests:test --tests ...` and forget that **`./gradlew build` includes `:integration-tests:test` too**. Hit 2026-08-03: a `build` run intended only to get Error Prone + spotless coverage sailed past `:fineract-provider:test` and headed straight into Cargo. It had to be killed. The build had already been running 19 minutes at that point and had not started the tests yet.

**Two correct shapes:**

```bash
# 1. Running tests — server already up, verify it first
curl -sk -o /dev/null -w '%{http_code}\n' https://localhost:8443/fineract-provider/actuator/health   # want 200
./gradlew :integration-tests:test -PcargoDisabled --tests 'org.apache.fineract.integrationtests.SomeTest'

# 2. Static/quality gates only — pass the property AND exclude the server-dependent suites
./gradlew build -PcargoDisabled -x rat -x :fineract-doc:asciidoctor -x :fineract-doc:asciidoctorPdf \
  -x :fineract-provider:cucumber -x :fineract-e2e-tests-runner:test \
  -x :integration-tests:test -x :oauth2-tests:test -x :twofactor-tests:test \
  --no-parallel --max-workers=1
```

**Don't play whack-a-mole with `-x` — pass the property.** `cargoDisabled` is checked by **three** modules, not one: `integration-tests/build.gradle:148`, `oauth2-tests/build.gradle:93,98` and `twofactor-tests/build.gradle`. One `-PcargoDisabled` disables Cargo in all of them. Excluding tasks one at a time does not: a build that excluded only `:integration-tests:test` still died on `Execution failed for task ':oauth2-tests:cargoStartLocal'` after 5m51s.

Both parts are needed for a static-gates build, and they do different jobs: `-PcargoDisabled` stops a Tomcat from being booted, while `-x <module>:test` stops the suite from running against a server that now isn't there.

The server-dependent suites are `integration-tests`, `oauth2-tests`, `twofactor-tests` and `fineract-e2e-tests-runner`. That last one has no Cargo wiring but still demands a live server on 8443 and dies with `feign.RetryableException: Connection refused ... /externalevents/configuration`, reported as an `initializationError` before any assertion runs.

**Corollary — the two modes are mutually exclusive.** Never run `build` while a `bootRun` server is live: it recompiles the provider's classes underneath the running JVM and every subsequent write 500s (reads keep working, which makes it worse to diagnose). Build first, then start the server, then run tests with `-PcargoDisabled`.

> **Apply**: every Gradle invocation in this repo. If the command contains `build` or `test` and does not contain `-PcargoDisabled` or `-x :integration-tests:test`, it is wrong. Related: #24 (a live server is what makes a test green), #30 (compile all consumers).

---

## 36. Prove the Workaround Is Necessary — Against the Live Server, Before You Write It

**Reviewer**: adamsaghy
**Context**: `LoanChargeCommandsApi.waiveLoanChargeInFull` was a bodyless Feign method justified by a note that the endpoint "answers a `{}` body with a 500". Review asked "I am not sure about this...". Re-probed against a running server:

```
POST /loans/{id}/charges/{chargeId}?command=waive
  body {}                -> 200      (flat fee, percent-of-amount fee, penalty)
  body {"locale":"en"}   -> 200
  empty body             -> 200
```

The 500 **did not reproduce**, and the server code explains why: for a non-instalment charge `LoanChargeWritePlatformServiceImpl.waiveLoanCharge` never reads the body, and `validateInstallmentChargeTransaction` accepts `{}` (no unsupported params, absent `installmentNumber` → `ignoreIfNull`). The whole file was deletable; `executeLoanChargeOnExistingCharge(loanId, chargeId, new PostLoansLoanIdChargesChargeIdRequest(), "waive")` works and is what `FeignLoanHelper` already did elsewhere.

**The tell that should have caught it earlier**: another migrated test in the same PR (`LoanChargeSpecificDueDateTest`) was already passing `new PostLoansLoanIdChargesChargeIdRequest()` — i.e. `{}` — to the same command and was green. Two places in one PR disagreeing about whether an endpoint accepts `{}` means one of them is wrong.

**Rule**: a hand-written Feign interface is escalation step 3 (#6). Before writing one, reproduce the failure that justifies it with a direct HTTP probe, and record the exact request/response in the javadoc. A remembered failure is not evidence — re-run it. When the workaround is later questioned, re-probe rather than defending the note.

> **Apply**: every `@RequestLine` interface in the test tree. If you cannot reproduce the failure it exists to dodge, delete the interface.

---

## 37. Sending an Explicit JSON `null`: Mark the Schema `nullable`, Don't Hand-Roll a Request Model

**Reviewer**: adamsaghy ("I wonder whether there is a way to handle such scenarios gracefully...")
**Context**: Clearing a loan product's delinquency bucket requires `{"delinquencyBucketId": null}` — the server gates on `command.parameterExists("delinquencyBucketId")`, so the key must be *present*. The mapper is globally `NON_NULL`, so the generated model dropped the field, and a bespoke `LoanProductCommandsApi` with a `@JsonInclude(ALWAYS)` field was written instead. Its javadoc claimed "marking the field `nullable` does not help — the generator emits a plain `@Nullable Long`". **That claim was wrong.**

**The mechanism**: with `nullable: true` on the property, openapi-generator emits `JsonNullable<T>`:
```java
private JsonNullable<Long> id = JsonNullable.<Long>undefined();   // omitted when untouched
public DelinquencyRange id(@Nullable Long id) { this.id = JsonNullable.of(id); }  // .id(null) -> "id": null
```
`DelinquencyRange.id` in this repo already proves it. `ObjectMapperFactory` registers `JsonNullableModule`, so it round-trips.

**Why it is safe**:
- **Source-compatible** — the model keeps plain `getX()` (`id.orElse(null)`) and the fluent `x(T)` setter, only *adding* `getX_JsonNullable()`. Existing callers compile unchanged.
- **Not a breaking API change** — measured with the real gate: adding `nullable: true` reported "No breaking API changes detected" (contrast #38).

```java
// nullable so the generated client can send an explicit JSON null
@Schema(example = "1", nullable = true)
public Long delinquencyBucketId;
```

> **Apply**: whenever the server distinguishes "field absent" from "field present and null" (`parameterExists`). Reach for `nullable = true` before writing any interface.

**Coda — it was still reverted here.** `nullable = true` is the right fix *for SDK consumers*, but it cannot fix these tests: the Retrofit model shadows the Feign one on the `integration-tests` classpath (#40), so the annotation never reaches the wire and `LoanProductCommandsApi` has to stay regardless. A schema change that no migrated test can benefit from is out of scope for a test-migration PR (#42), so the spec was left at develop's shape and the workaround's javadoc now records *why* rather than claiming a fix that does not land. Right mechanism, wrong PR.

---

## 38. swagger-brake R010 Fires on *Widening* a Request Type — Fix the Schema and Let the Gate Go Red

**Context**: `PostClientsClientIdChargesRequest.amount` was declared `Integer` while the server parses `BigDecimal` and the sibling `PostLoansLoanIdChargesRequest.amount` is already `number`. Fixing it is correct, but `verify-api-backward-compatibility` goes red:

```
R010  POST /v1/clients/{clientId}/charges
      amount type was changed from integer to number
```

`RequestTypeChangedRule` compares each request attribute's `type` with `equalsIgnoreCase` — it has no notion of direction, so *widening* what the server accepts (no existing client can break) is reported identically to a narrowing. Note also the workflow prints "informational only" in the report body but its final step is `if [ "$BREAKING_CHECK_OUTCOME" == "failure" ]; then exit 1`, so the job **does** fail.

**Reproduce it locally without the OOM** — the full spec is too big for the gate, so slice it to the paths/schemas you touched, build a "before" and "after" pair, and run the real task (it has no compile dependencies, so it is safe next to a live `bootRun`):
```bash
./gradlew :fineract-provider:checkBreakingChanges -PcargoDisabled \
  -PapiBaseline=/tmp/spec_base.json -PapiNew=/tmp/spec_new.json
```
Always include an identical-spec run as a negative control, or you cannot tell a clean result from a broken harness.

**Ruled by adamsaghy (2026-08-17): if it is the correct fix, make it and let the API check fail.**
That settles what earlier rounds treated as an open question — do not re-escalate it, and do not keep
a workaround alive to protect the gate. The red R010 is accepted as the known cost of correcting a
schema that was lying about what the server accepts.

**What this does and does not license.** The ruling is about a schema that is *wrong*, where widening
is the fix. It is not a licence to retype a model for one call site's convenience:

| situation | answer |
|---|---|
| Declared type contradicts what the server parses (`Integer` vs `BigDecimal`) | fix the schema, accept R010 |
| One operation needs a shape the others must not have (locale string `"50,05"` on a field 118 call sites send as a number) | own request model / typed interface — one schema cannot be both |
| Field is merely missing | additive, no R010 at all |

The live example of the second row is `LoanChargeCommandsApi`, which stays. The example of the first
is `PostClientsClientIdChargesRequest.amount`, where the retype replaced the former
`ClientChargeCommandsApi` workaround — that workaround is now deleted.

**Still measure before you push.** "Accepted" means one known violation you can name, not an unread
gate. Run the sliced reproduction above so the PR can state exactly which rule fires on which path,
and confirm nothing *else* went red by accident.

> **Apply**: any request-schema type change. Related: #28 (same situation for `@ApiResponse`), #42
> (each prod hunk still needs a test call site — the ruling relaxes the gate, not that requirement).

---

## 39. `null` Is Not "No Body" in Feign — and Don't Bundle Comment Tidying Into a Migration

Two unrelated traps from the same review round.

**(a) Feign rejects a null body argument.** The intuition "just don't set the request and it'll send null" is wrong:
```
IllegalArgumentException: Body parameter 1 was null
```
`MethodMetadata.bodyRequired` initialises to `true` (feign-core 13.12) and the stock `Contract.Default` never clears it, so `BuildEncodedTemplateFromArgs.resolve` throws before the request is built. To send `{}` pass an **empty request model**; to send genuinely no body, declare a method whose parameters are all `@Param`.

**(b) Comment tidying does not belong in a migration PR.** Dropping `// Given/When/Then` narration and method javadoc across 22 files drew "any reason to remove the comments?" on two files — and the honest answer was "no, and nothing replaced them" (assertion count was identical before and after). Reordering test methods in the same file made it worse: GitHub rendered a moved method as a large deletion, so the diff looked like far more had been removed than actually was.

Restoring them afterwards is **not** a mechanical job. Three ways a naive re-insertion goes wrong:
- javadoc for a helper method the migration **legitimately inlined** gets reattached to whatever declaration now follows (field javadoc landing on an unrelated field);
- a javadoc block gets split when `/**`, `*` and `*/` are treated as independent lines — an orphaned `*` does not compile;
- a comment lands *inside* a multi-line call, between the method and its arguments.

Anchor each block to the statement it described, treat `/** … */` as atomic, never stack two javadocs on one declaration, and drop blocks whose subject the migration genuinely deleted. Then verify: net comment delta vs upstream should be ~0 per file, and the reviewer-flagged file should diff as a pure migration.

> **Apply**: keep migrations mechanical. If a file needs tidying, that is a separate commit — ideally a separate PR.

---

## 40. The Retrofit SDK Shadows the Feign SDK in `integration-tests` — Same FQCN, First on the Classpath Wins

**The trap**: `fineract-client` (Retrofit, Gson, `@SerializedName`) and `fineract-client-feign` (Jackson, `@JsonProperty`) **both generate into `org.apache.fineract.client.models`**. `integration-tests/dependencies.gradle` declares them in that order:

```groovy
testImplementation project(path: ':fineract-client', configuration: 'runtimeElements')          // Retrofit — first
testImplementation(project(path: ':fineract-client-feign', ...)) { exclude module: 'fineract-client' }
```

The `exclude` only stops the *transitive* pull; the direct dependency above it still puts the Retrofit models first, so **every `org.apache.fineract.client.models.*` a Feign-based test touches is the Retrofit-generated class**, serialized by Jackson through plain bean introspection. It works by accident for ordinary fields (names coincide) and fails the moment a Jackson-specific annotation is what carries the meaning.

**How it bit**: the swagger fix for detaching a delinquency bucket (#37) made the *Feign* model a `JsonNullable<Long>`, verified through the real Feign encoder:

```
feign encoder body -> {"delinquencyBucketId":null}      # standalone, fineract-client-feign classes
PROBE body={}                                            # same code inside the test JVM
```

Same expression, two different classes. The Retrofit model is a plain `Long`, the mapper's global `NON_NULL` drops it, the body is `{}`, and the server never takes the clearing branch. The test failed with `expected: <null> but was: <814>` while both halves — client serialization *and* server behaviour — were provably correct in isolation.

**How to diagnose it**: when a request looks right but the server behaves as if a field never arrived, print the body *from inside the test JVM*, not from a standalone probe. If they disagree, you are looking at classpath shadowing, not a serialization bug.

**Why not just fix the ordering**: 46 test files still import Retrofit-only packages (`client.services.*`, `client.util.Calls`) and 430 import the shared `client.models` package, so dropping or reordering the Retrofit dependency silently reclassifies models under 430 files. That is its own ticket, not a review fix.

**How to apply**: a swagger `nullable`/Jackson-annotation fix is still the right change for real SDK consumers — keep it — but do **not** expect it to reach `integration-tests` until the shadowing is resolved. For the test, a small typed Feign interface with `@JsonInclude(ALWAYS)` remains necessary; say *why* in its javadoc, with the measured evidence, so the next reviewer does not delete it.

---

## 41. A 4-Character Random Short Name Is Not Unique Against a Long-Lived Dev Database

**Symptom**: `DelinquencyBucketsIntegrationTest.testLoanClassificationRealtime` failed with

```
403 error.msg.product.loan.duplicate.short.name
Loan product with short name `XA0K` already exists
```

while every other test in the class passed.

**Cause**: `baseDelinquencyLoanProductRequest` builds `shortName(Utils.uniqueRandomStringGenerator("", 4))`. "Unique" here means "random", not "checked" — and a local dev DB accumulates loan products across every run (including any created by manual probing), so a 4-character namespace collides eventually. CI gets away with it because it starts from a fresh database.

**Measured on this box**: `select count(*), count(distinct short_name), max(length(short_name)) from m_product_loan` → **3283 products, all 4 characters**. Against a 36⁴ namespace that is ~0.2% per creation; a full sweep creates several hundred products, so one or two collisions per run is the expected rate, not bad luck. Two occurred in a single session, in different classes (`DelinquencyBucketsIntegrationTest`, `DelinquencyActionIntegrationTests`).

**The same accumulation breaks paged searches — and this one does NOT clear on retry.** `SearchExternalAssetOwnerTransferTest` failed three of eight on `assertTrue(first.isPresent())`. It searches by *hardcoded* dates, so every previous run leaves another matching row. Measured:

```
select count(*) from m_external_asset_owner_transfer                    -> 639
select settlement_date, count(*) ... group by settlement_date           -> 2020-03-02 : 280
DEFAULT_SEARCH_PAGE_SIZE (FeignExternalAssetOwnerHelper)                -> 200, page 0
```

280 rows share the date the test hardcodes, the search asks for the first 200, and the transfer it just created is not among them. The narrow-filter sibling (`...UsingSubmittedDateTest`) passed, which is the tell.

**The retry behaviour is the diagnostic.** Random collisions (#41 above) pass on a second run; accumulation-vs-page-size failures reproduce every time. If a "flaky" test fails identically on re-run, stop calling it flaky and go count rows.

**How to read it**: a duplicate-key 403 on a *generated* identifier is environmental, not a regression. Do not bisect it and do not "fix" it by changing assertions — re-run, and if it recurs, widen the generated name. Distinguish it from a real failure by checking whether the rest of the class passed and whether the colliding value is random.

> **Apply**: any `duplicate.*` / data-integrity 403 in a local test run. Related: #24 (a live server is what makes a test green) and the reminder that local DB state is not CI's.

---

## 42. Every Prod-Code Hunk in a Test-Migration PR Must Point at a Test Call Site — Audit Them One by One

**Reviewer**: adamsaghy, repeatedly, across three PRs ("why is production code touched here?"). The answer cannot be a paragraph of intent; it has to be a call site.

**The audit**: list the non-test diff, then for each hunk find the migrated test that would not compile or would not pass without it.

```bash
git diff --stat upstream/develop..HEAD -- . ':(exclude)integration-tests/**'
grep -rn "\.<newField>(\|get<NewField>()" --include=*.java integration-tests/
```

Nine files survived the previous review round. Three did not survive the audit:

| hunk | verdict |
|---|---|
| `PostClientsClientIdChargesRequest.amount` `Integer`→`BigDecimal` | **keep** — `ClientChargeRoundingTest` sends `19.875` and asserts `19.88` |
| `PostClientsClientIdChargesChargeIdRequest.amount` same change | **revert** — the only consumer, `FeignChargesHelper.payClientCharge`, predates the branch and has zero callers; never sets `amount` |
| `delinquencyBucketId` `nullable = true` | **revert** — correct in principle (#37), unreachable in practice (#40) |
| `GetLoansLoanIdChargesChargeIdResponse.loanId` | ~~revert~~ → **keep** — reverted on a bad grep, then **CI failed the compile**; `ClientLoanIntegrationTest:259` asserts on `loanChargeDetail.getLoanId()`. See trap 3 |
| `InteropApiResource` `@RequestBody` + `LoanTransactionsApiResourceSwagger` → `public` | **keep** — `ClientLoanIntegrationTest:7846` calls `interOperation().loanRepayment(accountNo, request)`; without the schema the generated method takes no body |

**Two traps the audit itself has to avoid:**

1. **"Unused" needs the *branch* as the baseline.** `payClientCharge` looks like dead code the migration added; `git show upstream/develop:<file>` proves it was already there. Deleting it is a different PR.
2. **Do not revert on the reading alone — find the caller first.** The interop hunk looks exactly like a drive-by fix (it even corrects a copy-pasted `@Operation(summary = "Disburse Loan by Account Id")` on a *repayment* endpoint) and was one grep away from being reverted. One call site, in a 7800-line migrated test, is what saved it.
3. **Search for the *accessor*, not for the type — and do it repo-wide.** This one cost a red CI. Having grepped `GetLoansLoanIdChargesChargeIdResponse` repo-wide, I then searched `getLoanId()` *only inside the three files that grep returned*. `ClientLoanIntegrationTest` was not among them, because it reaches the type through the local variable `loanChargeDetail` on a line the type name never appears on. `compileTestJava` found it in 7m35s. Enumerate the variables, then search the calls:

```bash
grep -rnE "<Type> +([A-Za-z0-9_]+)" --include=*.java --exclude-dir=build . \
  | sed -E 's/.*<Type> +([A-Za-z0-9_]+).*/\1/' | sort -u \
  | while read v; do grep -rn "$v\.<accessor>()" --include=*.java --exclude-dir=build .; done
```

**Side benefit**: reverting the second `amount` halved the swagger-brake surface — one R010 violation instead of two, on the single hunk a test actually needs (#38). A red gate is much easier to defend when it is minimal and every line behind it has a caller.

**The honest limit of this whole lesson: grep is not a compiler.** Every revert here restores develop's shape, so the *only* thing that can break is branch-added code — which makes `compileTestJava` the cheap, total oracle and grep the expensive, partial one. Reverting a prod hunk without recompiling is guessing. If a local build is unaffordable, say the verification is a reasoned one rather than a measured one, and expect CI to be the real check.

> **Apply**: before every push on a migration branch. Response-model additions are the usual suspects — they feel free, they read as documentation improvements, and with `FAIL_ON_UNKNOWN_PROPERTIES=false` nothing forces them (until a test reads one back, as here).

---

## 43. A Re-Inserted Comment That Lost Its Anchor Reads as a Deleted Test Step

**What happened**: after #39(b) ("any reason to remove the comments?") the narration was put back
mechanically. The lines returned, the per-file comment delta went **positive** (+48 across the branch),
and the check "net delta ~0" passed — but the comments landed in the wrong places. Whole runs piled up
at the end of a method body:

```java
runPeriodicAccrualAccounting("04 March 2023");
// Client and Loan account creation
// Add Charge Penalty
// Add Charge Fee
// Run accrual for charge created date
// verify accrual transaction created for charges create date
checkAccrualTransaction(LocalDate.of(2023, 3, 4), 0.0, 10.0, 0.0, loanId);
```

The next review round read that as **deleted test steps** — "Any reason to remove half of the test
steps?" — which is a strictly worse objection than the one the re-insertion was meant to settle. A
comment with no matching statement under it is read as the epitaph of a statement someone deleted.

**So the delta check is necessary but not sufficient.** Two conditions, both required:
1. per-file comment delta vs upstream ≈ 0, **and**
2. every comment sits directly above the statement it describes.

**Where the steps were folded into a helper, the narration goes inside the helper, once.** Two tests
each calling `createBehindLoan()` do not need four lines of `// Create Client / // Create Loan Product
/ // Apply and Approve Loan / // disburse Loan` at both call sites — that legitimately makes the file
delta negative, and that is the correct answer, not a regression.

**Detector that actually finds it** (position-aware, not count-aware): a run of >= 2 comment lines
inside a method body whose members were *non-adjacent* in the base revision. Adjacency in base is what
separates a genuine wrapped/two-line comment from a collapsed pile.

```python
# base: text -> was it adjacent to another comment there?
# head: flag runs where every member existed in base and none was adjacent there
```

**Do not "fix" it by deleting the orphans.** That re-runs #39(b) straight into the same reviewer. The
fix is to re-anchor; deletion is only right for a block whose subject the migration genuinely removed.

> **Apply**: any time comments are restored after a migration. Verify by eye on the reviewer-flagged
> file, and by the detector everywhere else.

---

## 44. Assertion Counts Cannot Judge Coverage Loss Across a Rewrite — Domain Numbers Can

Asked to prove no test steps were lost across 90 migrated files, the obvious metric — assertions per
test, before vs after — was worse than useless in both directions:

- `transactionSummaryReportWithAssetOwner` fell **137 -> 18** and is *stronger* after: 120 inline
  `jsonPath.getString("data[0].row[3]")` calls became `assertReportRow(...)`, which checks all 11
  original columns plus a 12th.
- `testLoanCOBNoLock` fell **7 -> 0** and was a **real** loss: `verifyLoanIsPending/Approved/Active`
  vanished into an `applyApproveDisburse()` helper that asserts nothing.

Inlining helpers to compensate does not rescue the metric — following the superclass chain pulls in
`BaseLoanIntegrationTest`, and the transitive closure reports 437 "losses" that are all artefacts.

**What worked: the multiset of domain numeric literals per test** (decimals, and integers >= 10,
excluding years), with one level of in-file helper inlining. Money, rates, day counts and expected
balances survive a rename; they only disappear if an assertion actually disappeared. Across 90 files
it flagged **one** test — the 137 -> 18 one — and that turned out to be the strengthening above.

Two cheap exact checks belong alongside it, because both look like deletions in a diff and neither is:
- `@Test` **name-set** parity (a rename reads as "dropped test": `..._CheckFromAssetOwnerIdForBuyback`
  -> `...CheckFromAssetOwnerIdForBuyback`);
- count of lifecycle status verifications per file, which is where helper extraction actually leaks.

> **Apply**: whenever a reviewer asks "did we lose coverage?". Bring the numeric-footprint result and
> the `@Test` parity count; do not bring an assertion-count table.

---

## 45. A Migration Rewrites the Class Header — So Every Upstream Class-Level Annotation Collides

`FINERACT-2684` added exactly two lines to 28 integration-test classes: `import
org.junit.jupiter.api.Order;` and a class-level `@Order(1)`/`@Order(2)` (JUnit `ClassOrderer`, so the
long-job classes run first). Thirteen of those classes are on this branch, and **all thirteen
conflicted** — a one-line annotation is unmergeable against a migration that rewrites the import block
and the `extends` clause in the same hunk.

The resolution is mechanical and always the same shape: keep the migrated body, re-add the upstream
annotation, place the import in its alphabetical slot.

```java
// ours (new base)                          // theirs (the migration commit)
import ...common.LoanTransactionHelper;     import ...client.feign.FeignLoanTestBase;
import org.junit.jupiter.api.Order;         import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Test;
                                            public class X extends FeignLoanTestBase {
@Order(1)
public class X extends BaseLoanIntegrationTest {

// resolved
import ...client.feign.FeignLoanTestBase;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@Order(1)
public class X extends FeignLoanTestBase {
```

Two traps in the mechanical pass:

- **`@Order` is not inherited.** Where the migration made one test class extend another
  (`InitiateExternalAssetOwnerTransferTest extends ExternalAssetOwnerTransferTest`), the subclass
  still needs its own `@Order(1)`; taking "theirs" wholesale silently drops it.
- **A whole-file "take theirs" can swallow a real upstream fix.** The same upstream range added
  `manageConfigurations(ENABLE_AUTO_GENERATED_EXTERNAL_ID, false)` to a cleanup method the migration
  had deleted. Before resolving a file by taking one side entirely, check that the *other* side's
  change is genuinely covered — here it was, by per-test `finally` blocks the migration had already
  introduced, and by the base class's `cleanUpAndRestoreBusinessDate()`.

**Verify the whole batch in one command** rather than eyeballing thirteen headers:

```bash
for f in $(git show <upstream-sha> --name-only --pretty=format: | grep '\.java$'); do
  up=$(git show <upstream-sha>:$f | grep -c '^@Order(')
  mine=$(git show HEAD:$f       | grep -c '^@Order(')
  [ "$up" != "$mine" ] && echo "MISMATCH $f upstream=$up head=$mine"
done
```

`spotlessCheck` is a cheap second net here: Fineract's spotless runs `removeUnusedImports()`, so a
green run proves no resolution left a stale import behind — which is the most likely way one of these
merges breaks the compile.

> **Apply**: after any upstream range that touches class headers across many files (annotations,
> `@ExtendWith`, `@SuppressWarnings`). Resolve, then run the annotation-parity loop *and*
> `spotlessCheck`, before claiming the rebase is clean.

---

## Quick Pre-Submission Checklist

- [ ] No FQN references — all classes properly imported
- [ ] No magic numbers/strings — use constants or builder defaults
- [ ] Collection assertions use exact matching (remove-on-match), not `anyMatch`
- [ ] Null semantics are explicit — "must be null" vs "don't care" via overloads
- [ ] Helper methods placed at the right level (private in test vs shared in base/helper)
- [ ] Return full response objects from Feign helpers
- [ ] No REST-assured **and no `FeignRawHttpHelper`** introduced in Feign-based tests — `grep -n "FeignRawHttpHelper\|performServerPost\|RequestSpecification" <the PR diff>` must be empty
- [ ] Every missing model field fixed in the `*ApiResourceSwagger` DTO (command bodies and `changes` responses included), not worked around
- [ ] Every comment sits directly above the statement it describes — run the orphan detector (#43); a
      clean net delta is not enough
- [ ] Coverage-loss claims backed by the domain-number footprint and `@Test` name-set parity (#44),
      never by assertion counts
- [ ] Loan lifecycle status checks survive helper extraction — `verifyLoanIs{Pending,Approved,Active}`
      folded into an `applyApproveDisburse`-style helper must be re-added inside it
- [ ] Verify generated model has all required fields before using `createXxxFromJson`
- [ ] Side effects verified (delete → confirm 404, reject → confirm status)
- [ ] Assertions check specific identity, not just non-emptiness
- [ ] No unused aliases or dead code
- [ ] Migrated getters read the *same* business quantity as the original JSON field (not just a similar name)
- [ ] No verification silently dropped because "the model lacks the field" — add the field to the Swagger DTO
- [ ] No expected numeric value changed in a pure refactor (diff literals vs pre-migration)
- [ ] `assertThrows` on error cases also asserts the exact `getStatus()` the original response spec pinned
- [ ] No `.legacy` / retrofit `Calls` usage in Feign helpers; use `fineractClient.xxxApi()` + `ok(...)`
- [ ] Fix server OpenAPI `@ApiResponse` schema instead of introducing custom client interface workarounds
- [ ] Return strongly-typed generated DTO models instead of untyped `HashMap<String, Object>` wrappers
- [ ] **Every touched integration test actually RUN and PASSED against a live server (`-PcargoDisabled`)** — compile + spotless is not verification
- [ ] Before deleting a "shadowing" field, classify each usage (static-call qualifier / unused / real instance call) — remove only the dead ones, then compile *and* run
- [ ] When squashing fixes into their originating commits, verify with `git diff <fully-fixed-snapshot> HEAD` (must be empty), not by eyeballing the rebase
- [ ] After any Swagger/`@ApiResponse` change, compile **all** SDK-consuming modules (`integration-tests`, `fineract-e2e-tests-core`, `fineract-e2e-tests-runner`) — not just the one you edited
- [ ] After rebase, run `git diff develop` (not `git diff HEAD`) to see the full PR footprint and classify every changed file
- [ ] After reverting a schema type, grep the entire repo for the old type name to find broken callers in other modules
- [ ] No query silently switched to/from `paged=true` — the paged read path may not apply the same filters (`orphansOnly`)
- [ ] No element-wise comparison of a listing from an endpoint that was given no explicit sort — its row order is not contractual
- [ ] Every hand-written `@RequestLine` interface has a *reproduced* failure recorded in its javadoc — re-probe before defending one under review
- [ ] "Present with null" vs "absent" handled by `@Schema(nullable = true)` (→ `JsonNullable<T>`), not a bespoke request model with `@JsonInclude(ALWAYS)`
- [ ] Request-schema type changes checked against `checkBreakingChanges` on a sliced spec (with an identical-spec control) before claiming they are safe
- [ ] No `null` passed as a Feign body argument — Feign throws `Body parameter N was null`; use an empty model, or an all-`@Param` method for no body
- [ ] No comment/formatting tidying or method reordering mixed into a migration commit — net comment delta vs upstream should be ~0 per file
- [ ] After a rebase over an upstream range that touched class headers, every `@Order`/`@ExtendWith`
      annotation upstream added is still present — check with the parity loop in #45, not by eye
- [ ] **Every Gradle command carries `-PcargoDisabled`** (or `-x :integration-tests:test`) — without it, `test` *and* `build` boot their own Tomcat and run the whole suite for hours; add `-x :fineract-e2e-tests-runner:test` too, and never run `build` while a `bootRun` server is live
- [ ] **Every hunk in `git diff develop -- . ':(exclude)integration-tests/**'` has a named test call site** — grep for the field's setter/getter; revert anything with none, and check `git show develop:<file>` before calling a helper method "dead code the branch added"
