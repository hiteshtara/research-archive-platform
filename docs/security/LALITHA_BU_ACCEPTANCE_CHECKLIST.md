# BU Acceptance Checklist: Kuali Research Archive security

**For:** Lalitha (business acceptance tester)
**Where to test:** https://nonprod.kuali-archive.bu.edu
**Source:** *Kuali Archive Enhancements and Requirements*, "Security Admin User Stories" tab. All 47 acceptance criteria are copied below word for word, with their story IDs and numbers.
**Prepared:** 2026-11-17

---

## Read this first

> **Record-level security is OFF on the test site today, and switching it on has not been approved yet.**
>
> When security is off, everyone sees every record. Any check below that says
> someone should *not* see a record will fail, or will seem to pass for the
> wrong reason. Run the "should not see" checks only during a **controlled
> testing window**: a scheduled time when the administrator has switched
> record-level security on for the test site. The administrator will tell you
> when that window starts and ends.
>
> Until that window is approved and scheduled, mark these checks
> **NOT TESTABLE YET**. Do not mark them PASS or FAIL.

- **You never set up accounts or access.** The administrator creates every test account and every grant before you start. If something on the "Before you start" list is missing, stop and ask. Do not try to work around it.
- **Use only the test accounts listed below.** Do not test with your own BU account.
- **"Cannot open" should look like "not found".** If a record is blocked, the page should look as though the record does not exist. It should not say "access denied", and it must not show the title, PI, sponsor or any other detail. If it shows any detail, that is a **FAIL**.
- **For every check, record a result:** PASS, FAIL, or NOT TESTABLE YET, plus a short note, such as what you saw or a screenshot name.

---

## Before you start (the administrator prepares this)

### Test accounts

The spreadsheet's "Test Case Examples" section names the BU people these accounts represent. This document deliberately leaves those names out. The administrator matches each account to the right person.

| Account | Represents | Access the administrator grants |
|---|---|---|
| **T-CEN** | Central Office user | CENTRAL (all records) |
| **T-DEPT** | Department Administrator, lead unit 2573180018 (who is also the PAFO administrator on Awards A, B and C) | UNIT 2573180018, without sub-units |
| **T-DEPT2** | Department Administrator with two departments | UNIT 2573180018 **and** UNIT 1330420000, without sub-units |
| **T-PI** | Principal Investigator (Research Staff) | Research-staff access (CONTACT_DERIVATION), linked to the PI's Kuali person ID |
| **T-OAV** | Other Authorized Viewer | IO 9500312705 |
| **T-NONE** | Signed-in person with no access | No grant at all |

> Research-staff roles: which Kuali roles count as "Research Staff" (PI only,
> or PI plus Co-PI, Co-Investigator and Key Person) is **still an open BU
> decision**. Use the role set that is configured for the test window, and
> write it in the Notes column of check S4.

### Test records (the spreadsheet's examples)

| Label | Award number | Account (IO) | Lead unit | Who should see it |
|---|---|---|---|---|
| **A** | 212128-00002 | 9500316722 | 2573180018 | Central, Department, PI |
| **B** | 211144-00007 | 9500317253 | 2573180018 | Central, Department. **Not** the PI (B has a different PI), **not** the OAV |
| **C** | 209407-00002 | 9500312705 | 1330420000 | Central, OAV. **Not** the Department user, even though they are listed as PAFO administrator on it |

The administrator also gives you a **direct link** (`https://nonprod.kuali-archive.bu.edu/awards/<internal award id>`) for A, B and C. They copy each link while signed in as Central, so you can test opening a record you should not be able to find in search.

| Direct link to A | Direct link to B | Direct link to C |
|---|---|---|
| | | |

---

## Part 1: The six named scenarios

For each scenario, sign out completely, then sign in as the account shown.

### S1: Central user sees everything (T-CEN)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| S1.1 | Open **Dashboard** (home page). Write down the Awards count. | The count is the archive-wide total. Compare it with the other accounts' counts later. | | |
| S1.2 | **Awards → Award Search**: search 212128-00002, then 211144-00007, then 209407-00002. | Each one is found. | | |
| S1.3 | Open each of A, B and C. Open the **Versions/History** section and the **Attachments** section. | All open. History lists every version. Attachments are listed and can be downloaded. | | |

### S2: Department user sees only their department (T-DEPT)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| S2.1 | Award Search for 212128-00002 (A) and 211144-00007 (B). | Both found. | | |
| S2.2 | Open A and B. Check Versions/History and Attachments. | Both open. B shows its earlier version as well. Attachments download. | | |
| S2.3 | Award Search for 209407-00002 (C). | **Not found**, even though this user is the PAFO administrator on C. | | |
| S2.4 | Paste the **direct link to C**. | Looks like "not found". No title, PI, sponsor or other detail of C. | | |
| S2.5 | Dashboard Awards count. | Smaller than Central's. It counts only this department's Awards. | | |

### S3: Department user with two departments (T-DEPT2)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| S3.1 | Award Search for A, B and C. | All three found: A and B through unit 2573180018, C through unit 1330420000. | | |
| S3.2 | Search for an Award whose lead unit is 1330000000 (the parent of 1330420000). The administrator supplies one. | **Not found.** Neither grant includes sub-units or the parent unit. | | |
| S3.3 | Dashboard Awards count. | Equals T-DEPT's count plus the Awards in unit 1330420000. | | |

### S4: Principal Investigator sees only their own records (T-PI)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| S4.1 | Award Search for 212128-00002 (A). | Found. | | Role set in use: |
| S4.2 | Open A. Check History and Attachments. | Opens. Attachments download. | | |
| S4.3 | Award Search for 211144-00007 (B). | **Not found**, even though B is in the same department as A. | | |
| S4.4 | Paste the **direct link to B**. | Looks like "not found", with no details. | | |
| S4.5 | Dashboard Awards count. | Counts only this PI's Awards. | | |

### S5: Other Authorized Viewer sees only the approved IO (T-OAV)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| S5.1 | Award Search for 209407-00002 (C). | Found. | | |
| S5.2 | Open C. Check History and Attachments. | Opens. History lists every version. Attachments download. | | |
| S5.3 | Award Search for 211144-00007 (B, account 9500317253). | **Not found.** | | |
| S5.4 | Paste the **direct link to B**. | Looks like "not found", with no details. | | |

### S6: No access, and access that is taken away (T-NONE, then T-OAV)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| S6.1 | Sign in as **T-NONE**. Open the Dashboard and Award Search. | A clear "access not provisioned" message. **Not** an empty archive and not a list of records. | | |
| S6.2 | As T-NONE, paste the direct link to A. | No record details. | | |
| S6.3 | Ask the administrator to **remove** T-OAV's IO 9500312705 access. Then sign in again as T-OAV and repeat S5.1. | C is no longer found, and the direct link to C shows no details. | | |
| S6.4 | Ask the administrator to restore T-OAV's access (clean-up). | Done. | | |

---

## Part 2: All 47 BU acceptance criteria

The "Criterion" column is copied word for word from the spreadsheet; the criterion number is in the "#" column. "How to check" points to Part 1 where a scenario already covers the criterion.

### Central User

**US-CEN-001**: As a Central Office user, I want access to all historical grants and agreements records across BU so that I can support institution-wide research administration activities.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | User can search all archived records. | T-CEN | Search Awards, Proposals, Negotiations and Subawards, plus the top **Search** page. | Results from every module. | | |
| 2 | User can view records from any department or lead unit. | T-CEN | S1.2 to S1.3 (A and B are in 2573180018, C is in 1330420000). | All open. | | |
| 3 | User can access proposals, awards, agreements, subawards, and attachments. | T-CEN | Open one record of each kind and download one attachment. | All open. Attachment downloads. | | |
| 4 | Access is read-only. | T-CEN | Look for Edit, Delete or Upload buttons on a record. | None exist. | | |

**US-CEN-002**: As a Central Office user, I want to retrieve historical documents and attachments so that I can respond to sponsor requests, audits, compliance reviews, and administrative inquiries.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | User can open documents directly from a record. | T-CEN | Open Award A, go to Attachments, open one. | Opens. | | |
| 2 | User can download attachments. | T-CEN | Download an attachment from A, B or C. | File downloads. | | |
| 3 | Document retrieval does not require system administrator assistance. | T-CEN | Steps 1 and 2 above, done without help. | No help needed. | | |

**US-CEN-003**: As a Central Office user, I want to view historical record activity and supporting documentation so that I can investigate historical business decisions and events.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | User can view historical record details. | T-CEN | Open C's History/Versions. | Every version is listed and opens. | | |
| 2 | User can view linked historical records. | T-CEN | On A, open the linked Proposal, Negotiation or Subaward sections. | Linked records are listed and open. | | |
| 3 | User can view approvals and supporting documentation where retained. | T-CEN | On an Award, open its workflow/approval and attachment sections. | Shown where the archive holds them. | | |

**US-CEN-004**: As a Central Office user, I want to search records across departments and schools so that I can support BU-wide business processes.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Search supports department, school, PI, sponsor, proposal, and award criteria. | T-CEN | In Award Search, try each filter or search term: lead unit, PI name, sponsor, proposal number, award number. | Each one narrows the results correctly. | | Note any criterion with no filter. |
| 2 | Search results return records across the institution. | T-CEN | Search a common sponsor. | Results come from several units. | | |

### Department User

**US-DEP-001**: As a Department Administrator, I want access to historical records associated with my authorized department so that I can support faculty and research administration activities.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | User can only see records within assigned lead units. | T-DEPT | S2.1 to S2.3 | A and B only. | | |
| 2 | User cannot see records from unauthorized departments. | T-DEPT | S2.3 to S2.4 | C not found, and its direct link shows nothing. | | |
| 3 | User may be assigned to multiple departments. | T-DEPT2 | S3.1 to S3.3 | Both departments are visible, and nothing beyond them. | | |

**US-DEP-002**: As a Department Administrator, I want to retrieve historical records without submitting requests to Sponsored Programs so that I can support faculty more efficiently.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | User can search and retrieve records independently. | T-DEPT | S2.1 to S2.2 | Works without help. | | |
| 2 | No intervention from a central office is required. | T-DEPT | S2.2 (download an attachment) | No request needed. | | |

**US-DEP-003**: As a Department Administrator, I want access to historical award information for my department so that I can support award administration and reporting activities.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | User can access historical award records. | T-DEPT | Open B's History. | Both versions are listed. | | |
| 2 | User can access related documents and amendments. | T-DEPT | Open B's Attachments and its earlier version. | Available. | | |
| 3 | User can search by PI, sponsor, department, and award number. | T-DEPT | Search by each. | Results come only from 2573180018. | | |

**US-DEP-005**: As a Department Administrator, I want the archive to only display records I am authorized to view so that confidential research information remains protected.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Unauthorized records are not visible. | T-DEPT | S2.3 to S2.4. Also open A and look at its **related-record lists** (Proposals, Subawards, Negotiations). | C is not visible anywhere. | | Known issue below |
| 2 | Search results only return authorized records. | T-DEPT | Award Search with an empty search, then the top **Search** page with a common word. | Only A, B and other 2573180018 records. | | |

### Research Staff User

**US-PI-001**: As a Principal Investigator, I want access to my historical proposals and awards so that I can reference prior sponsored research activities.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | PI can search records associated with themselves. | T-PI | S4.1, plus Proposal search for the PI's proposals. | Found. | | |
| 2 | PI cannot access records belonging to other investigators. | T-PI | S4.3 to S4.4 | B not found, and its direct link shows nothing. | | |
| 3 | Access is read-only. | T-PI | Look for Edit, Delete or Upload buttons. | None. | | |

**US-PI-002**: As a Principal Investigator, I want access to my historical proposal documents so that I can use prior submissions as references for future proposals.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | PI can access proposal packages. | T-PI | Open one of the PI's Proposals. | Opens. | | |
| 2 | PI can access proposal attachments. | T-PI | Open that Proposal's Attachments. | Listed. | | |
| 3 | PI can download authorized documents. | T-PI | Download one Proposal attachment and one of A's attachments. | Both download. | | |

**US-PI-003**: As a Principal Investigator, I want access to my historical award information so that I can understand previous funding and sponsor relationships.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | PI can view award history. | T-PI | S4.2 | Opens. | | |
| 2 | PI can search by sponsor and project title. | T-PI | Search A's sponsor, then A's title. | A is found. B is not. | | |
| 3 | PI can view associated award documents. | T-PI | S4.2 Attachments | Available. | | |

### OAV User

**US-OAV-001**: As an Other Authorized Viewer, I want access to specifically approved records outside my normal organization so that I can perform approved business activities.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Access is limited to approved IO numbers. | T-OAV | S5.1 to S5.3 | Only C (9500312705). | | |
| 2 | Unauthorized records are not displayed. | T-OAV | S5.3 to S5.4 | B not found, and its direct link shows nothing. | | |
| 3 | Access expires when authorization is removed. | T-OAV | S6.3 | C disappears straight after removal. | | |

### Security Admin User

These checks are done **with the administrator**. The administrator does the steps in the Security Administration screens. You watch and record the result.

**US-SEC-001**: As a Security Administrator, I want archive access to be granted based on role, department assignment, system object assignment, and approved IO authorization so that security can be managed consistently.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Access can be assigned at role level. | Admin | Administrator shows the grants behind T-CEN, T-DEPT and T-PI (Central, Department and Research Staff). | Each is a separate kind of grant. | | |
| 2 | Access can be assigned at specific IO level. | Admin | Administrator shows T-OAV's IO 9500312705 grant. | Present. | | |

**US-SEC-002**: As a Security Administrator, I want archive permissions to be automatically updated when assignments change so that access remains accurate.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Department changes update permissions automatically. | Admin + T-DEPT | Administrator changes T-DEPT's unit. T-DEPT signs in again and searches. | New unit visible, old unit gone, with no other step. | | Note **how** the change was made: automatic, or by hand by the administrator. |
| 2 | Role changes update permissions automatically. | Admin + T-PI | Administrator changes T-PI's grant type. T-PI signs in again. | Access matches the new role. | | As above |
| 3 | Revoked authorization removes access automatically. | Admin + T-OAV | S6.3 | Removed at once. | | |

**US-SEC-003**: As a Security Administrator, I want unauthorized access attempts to be blocked so that historical records remain secure.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Unauthorized records cannot be opened. | T-DEPT, T-PI, T-OAV | S2.4, S4.4, S5.4 | Every one looks like "not found". | | |
| 2 | Unauthorized searches do not return restricted records. | T-DEPT, T-PI, T-OAV | S2.3, S4.3, S5.3, and the top **Search** page | Restricted records never appear. | | |

**US-SEC-004**: As a Security Administrator, I want all archive activity to be auditable so that security investigations can be performed when necessary.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Record views are logged. | Admin | After S2.2, administrator opens **Admin → Audit**. | T-DEPT's views of A and B appear, showing **who** viewed. | | Known issue below |
| 2 | Document downloads are logged. | Admin | After S2.2's download. | The download appears, showing who. | | Known issue below |
| 3 | Security changes are logged. | Admin | After S6.3 (removal) and S6.4 (restore). | Both changes appear, showing who made them. | | |

**US-SEC-005**: As a Security Administrator, I want the archive to be read-only so that historical records cannot be altered.

| # | Criterion | Account | How to check | Expected | Result | Notes |
|---|---|---|---|---|---|---|
| 1 | Records cannot be edited. | Any | Look for Edit controls on Award, Proposal, Negotiation and Subaward pages. | None. | | |
| 2 | Records cannot be deleted. | Any | Look for Delete controls. | None. | | |
| 3 | Historical documents cannot be modified. | Any | Look for Upload, Replace or Delete on attachments. | None. Download only. | | |

---

## Known issues: expect these to FAIL until their fixes are deployed

Engineering is fixing these separately. If you hit one, mark it **FAIL** and put the issue number from this list in Notes, so it is not reported twice.

1. **Related-record lists are not filtered yet.** An Award's linked Proposals, Subawards and Negotiations, and a Proposal's funded Awards, may show records the user cannot open. This affects US-DEP-005 #1, US-SEC-003 and US-CEN-003 #2 for restricted accounts.
2. **IRB records and the investigator list are not limited by department.** This affects any check that browses IRB or the investigator list as T-DEPT, T-PI or T-OAV.
3. **The activity audit does not yet record *who* acted.** This affects US-SEC-004 #1 and #2.

## Sign-off

| | Name | Date | Overall result |
|---|---|---|---|
| Tester | | | |
| Administrator (set-up and controlled window) | | | |
