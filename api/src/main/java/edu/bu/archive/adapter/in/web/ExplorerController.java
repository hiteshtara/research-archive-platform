package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardContactsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerRolodexResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitResponse;
import edu.bu.archive.application.award.ExplorerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/*
 * Archive Explorer, Phase 2 (web) - read-only, fixed/predefined queries
 * only (no arbitrary-SQL path exists anywhere in this class or
 * ExplorerService), authenticated via the same Cognito filter chain
 * every other /api/** route already uses (see SecurityConfiguration -
 * no extra wiring needed here). Gated behind app.explorer.enabled
 * (APP_EXPLORER_ENABLED), default false - disabled entirely (the whole
 * controller bean, and therefore every route below, never registers)
 * unless explicitly turned on, dev-only for now. Runs in-process
 * against the same repositories/PostgreSQL connection the rest of the
 * API uses - never a per-request Fargate task, unlike Phase 1's
 * ECS-mediated CLI (etl/archive_etl/explorer.py,
 * scripts/run-archive-explorer.sh), which remains the maintenance/admin
 * tool. See docs/ARCHIVE_EXPLORER.md.
 */
@RestController
@RequestMapping("/api/v1/explorer")
@ConditionalOnProperty(name = "app.explorer.enabled", havingValue = "true")
@Validated
@Tag(
        name = "Archive Explorer",
        description = "Read-only, dev-only developer tool - fixed "
                + "queries over already-archived data, no arbitrary SQL."
)
public class ExplorerController {

    private final ExplorerService service;

    public ExplorerController(ExplorerService service) {
        this.service = service;
    }

    @Operation(summary = "Look up an Award's current version by award_number")
    @GetMapping("/awards")
    public ResponseEntity<ExplorerAwardResponse> award(
            @RequestParam @NotBlank String awardNumber
    ) {
        return ResponseEntity.ok(service.findAward(awardNumber));
    }

    @Operation(summary = "Look up one specific archived Award version by award_id")
    @GetMapping("/award-versions")
    public ResponseEntity<ExplorerAwardResponse> awardVersion(
            @RequestParam @Positive long awardId
    ) {
        return ResponseEntity.ok(service.findAwardVersion(awardId));
    }

    @Operation(
            summary = "Look up which Award version owns a workflow document number",
            description = "Searches every archived version, not just "
                    + "the current one."
    )
    @GetMapping("/workflows")
    public ResponseEntity<AwardDocumentNumberMatchResponse> workflow(
            @RequestParam @NotBlank String documentNumber
    ) {
        return ResponseEntity.ok(service.findWorkflow(documentNumber));
    }

    @Operation(summary = "Look up a Unit and its Unit Administrators by unit_number")
    @GetMapping("/units")
    public ResponseEntity<ExplorerUnitResponse> unit(
            @RequestParam @NotBlank String unitNumber
    ) {
        return ResponseEntity.ok(service.findUnit(unitNumber));
    }

    @Operation(summary = "List a Unit's Unit Administrators by unit_number")
    @GetMapping("/unit-administrators")
    public ResponseEntity<List<ExplorerUnitAdministratorResponse>>
            unitAdministrators(@RequestParam @NotBlank String unitNumber) {
        return ResponseEntity.ok(
                service.findUnitAdministrators(unitNumber)
        );
    }

    @Operation(
            summary = "Key Personnel / Unit Details / Unit Contacts / "
                    + "Sponsor Contacts / Central Administration "
                    + "Contacts for one Award version",
            description = "Aggregates the same public Award Contacts "
                    + "endpoints (/people, /unit-details, "
                    + "/unit-contacts, /sponsor-contacts, "
                    + "/central-administration-contacts) into one "
                    + "navigable Explorer result."
    )
    @GetMapping("/award-contacts")
    public ResponseEntity<ExplorerAwardContactsResponse> awardContacts(
            @RequestParam @Positive long awardId
    ) {
        return ResponseEntity.ok(service.findAwardContacts(awardId));
    }

    @Operation(summary = "Look up an archived Person by person_id")
    @GetMapping("/persons")
    public ResponseEntity<ExplorerPersonResponse> person(
            @RequestParam @NotBlank String personId
    ) {
        return ResponseEntity.ok(service.findPerson(personId));
    }

    @Operation(summary = "Look up a Rolodex entry by rolodex_id")
    @GetMapping("/rolodex")
    public ResponseEntity<ExplorerRolodexResponse> rolodex(
            @RequestParam @Positive long rolodexId
    ) {
        return ResponseEntity.ok(service.findRolodex(rolodexId));
    }

    @Operation(
            summary = "Look up current-version Awards by sponsor_code",
            description = "sponsor_code/sponsor_name are denormalized "
                    + "directly onto archive.award_version - there is "
                    + "no separate Sponsor reference table in this "
                    + "archive - so this is Award-backed, not "
                    + "Rolodex-backed. Different from an Award's own "
                    + "Sponsor Contacts, which is already covered by "
                    + "/award-contacts' sponsorContacts field and the "
                    + "public /sponsor-contacts endpoint."
    )
    @GetMapping("/sponsors")
    public ResponseEntity<List<ExplorerAwardResponse>> sponsors(
            @RequestParam @NotBlank String sponsorCode
    ) {
        return ResponseEntity.ok(service.findSponsorsByCode(sponsorCode));
    }

    @Operation(summary = "List an Award's attachment metadata (capped at 50)")
    @GetMapping("/attachments")
    public ResponseEntity<List<AwardAttachmentResponse>> attachments(
            @RequestParam @Positive long awardId
    ) {
        return ResponseEntity.ok(service.findAttachments(awardId));
    }

    @Operation(
            summary = "Discover Institutional Proposals by attachment/"
                    + "funded-Award/amount/sponsor/unit/status filters",
            description = "One row per ACTIVE Proposal version family, "
                    + "relational joins only (no embeddings/vector "
                    + "search, no arbitrary SQL). Resolves any linked "
                    + "Award to its CURRENT version and that version's "
                    + "latest amount snapshot - never joins the full "
                    + "amount history, so a Proposal's own result row is "
                    + "never duplicated. Every filter is optional; "
                    + "omitting one means \"don't filter on it\"."
    )
    @GetMapping("/proposals")
    public ResponseEntity<List<ExplorerProposalDiscoveryResponse>> proposals(
            @Parameter(description = "true = only Proposals with at "
                    + "least one persisted attachment; false = only "
                    + "those with none; omitted = no filter.")
            @RequestParam(required = false)
            Boolean hasAttachments,

            @Parameter(description = "true = only Proposals with at "
                    + "least one active AWARD_FUNDING_PROPOSALS "
                    + "relationship; false = only those with none; "
                    + "omitted = no filter.")
            @RequestParam(required = false)
            Boolean hasFundedAward,

            @Parameter(description = "Only Proposals whose linked "
                    + "Award's current amount (obligated, falling back "
                    + "to anticipated) exceeds this threshold.")
            @RequestParam(required = false)
            BigDecimal minimumAwardAmount,

            @Parameter(description = "Exact sponsor_code match.")
            @RequestParam(required = false)
            String sponsorCode,

            @Parameter(description = "Case-insensitive substring match "
                    + "against sponsor_name - use this (not "
                    + "sponsorCode) for a sponsor like \"NIH\", which "
                    + "spans many institute-specific codes in the "
                    + "source data.")
            @RequestParam(required = false)
            String sponsorName,

            @RequestParam(required = false)
            String leadUnitNumber,

            @Parameter(description = "Matches proposal_version."
                    + "status_description exactly (e.g. \"Funded\").")
            @RequestParam(required = false)
            String proposalStatus,

            @Parameter(description = "Case-insensitive substring match "
                    + "against the Proposal's PI full name.")
            @RequestParam(required = false)
            String personName,

            @Parameter(description = "Exact match, e.g. \"New\", "
                    + "\"Renewal\", \"Continuation\", \"Resubmission\".")
            @RequestParam(required = false)
            String proposalType,

            @Parameter(description = "Exact match, e.g. \"Research\", "
                    + "\"Training\", \"Research Training\".")
            @RequestParam(required = false)
            String activityType,

            @Parameter(description = "Only Proposals whose project "
                    + "period (initial_start_date..total_end_date) "
                    + "overlaps [dateFrom, dateTo].")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,

            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @Parameter(description = "Page size, 1-100.")
            @RequestParam(defaultValue = "50")
            @Min(1)
            int size
    ) {
        return ResponseEntity.ok(
                service.findProposalDiscovery(
                        hasAttachments,
                        hasFundedAward,
                        minimumAwardAmount,
                        sponsorCode,
                        sponsorName,
                        leadUnitNumber,
                        proposalStatus,
                        personName,
                        proposalType,
                        activityType,
                        dateFrom,
                        dateTo,
                        page,
                        size
                )
        );
    }
}
