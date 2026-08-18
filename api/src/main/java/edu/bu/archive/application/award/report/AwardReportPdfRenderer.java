package edu.bu.archive.application.award.report;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAssociatedNegotiationResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetVersionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentCategoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingProposalResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingSubawardResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardNotepadEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;

import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/*
 * Renders an AwardReportData into a PDF, using OpenPDF's object API
 * (Document/PdfPTable/Chunk/Phrase) exclusively - every archived value
 * is added as literal text content, never parsed as HTML/markup, which
 * is what rules out formula/HTML/template injection structurally
 * rather than by escaping discipline. See CLAUDE.md's AI-features
 * section for this codebase's parallel philosophy of never trusting
 * generated/archived text as anything but plain data.
 */
@Component
public class AwardReportPdfRenderer {

    private static final Color BU_MAROON = new Color(0x8b, 0x18, 0x32);
    private static final Color TEXT_DARK = new Color(0x1e, 0x24, 0x30);
    private static final Color TEXT_MUTED = new Color(0x5a, 0x63, 0x72);
    private static final Color TABLE_HEADER_BG = new Color(0x8b, 0x18, 0x32);
    private static final Color TABLE_HEADER_TEXT = Color.WHITE;
    private static final Color TABLE_ALT_ROW = new Color(0xf5, 0xf6, 0xf8);
    private static final Color TABLE_BORDER = new Color(0xe7, 0xe9, 0xee);

    private static final Font COVER_TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 26, BU_MAROON);
    private static final Font COVER_SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA, 14, TEXT_DARK);
    private static final Font COVER_LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, TEXT_MUTED);
    private static final Font COVER_VALUE = FontFactory.getFont(FontFactory.HELVETICA, 12, TEXT_DARK);
    private static final Font COVER_NOTICE = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, TEXT_MUTED);
    private static final Font SECTION_HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, BU_MAROON);
    private static final Font SUBSECTION_HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, TEXT_DARK);
    private static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, TABLE_HEADER_TEXT);
    private static final Font TABLE_BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, TEXT_DARK);
    private static final Font EMPTY_STATE_FONT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, TEXT_MUTED);
    private static final Font MONOSPACE_FONT = FontFactory.getFont(FontFactory.COURIER, 7.5f, TEXT_DARK);
    private static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, TEXT_MUTED);
    private static final Font FIELD_LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, TEXT_MUTED);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter GENERATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    // Bounds any single free-text/XML field (comments, notepad entries,
    // raw SAP payloads) so one pathological source value cannot blow up
    // page count or memory - matches the AI feature's own
    // max-serialized-context-chars truncation philosophy.
    private static final int FREE_TEXT_TRUNCATE_LIMIT = 4000;

    public void render(AwardReportData data, OutputStream outputStream) throws DocumentException {
        Document document = new Document(PageSize.LETTER, 42, 42, 56, 56);
        PdfWriter writer = PdfWriter.getInstance(document, outputStream);
        writer.setPageEvent(new ReportFooterEvent(data.summary().awardNumber()));

        // Document properties are set explicitly to known-safe values -
        // never left to library defaults - so the PDF's own metadata
        // (visible via "Document Properties" in any PDF viewer) carries
        // the exact Award number and nothing else: no source file
        // paths, usernames, S3 locations, or other archive internals.
        document.addTitle("Award " + text(data.summary().awardNumber()) + " Complete Report");
        document.addSubject("Read-only legacy research administration archive report");
        document.addAuthor("Boston University Research Archive Platform");
        document.addCreator("Research Archive Platform");

        document.open();

        addCoverPage(document, data);
        addExecutiveSummary(document, data);
        addVersionHistory(document, data);
        addPeopleAndUnits(document, data);
        addFundingProposals(document, data);
        addRelatedSubawards(document, data);
        addRelatedNegotiations(document, data);
        addAmountHistory(document, data);
        addBudgetData(document, data);
        addTimeAndMoney(document, data);
        addTerms(document, data);
        addCustomData(document, data);
        addCommentsAndNotepad(document, data);
        addSapTransmissionHistory(document, data);
        addSourceReferenceAppendix(document, data);

        document.close();
    }

    private void addCoverPage(Document document, AwardReportData data) throws DocumentException {
        AwardSummaryResponse summary = data.summary();

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(60);
        document.add(spacer);

        Paragraph title = new Paragraph("Complete Award Report", COVER_TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        Paragraph awardNumber = new Paragraph(text(summary.awardNumber()), COVER_SUBTITLE);
        awardNumber.setAlignment(Element.ALIGN_CENTER);
        awardNumber.setSpacingBefore(6);
        awardNumber.setSpacingAfter(40);
        document.add(awardNumber);

        PdfPTable coverTable = new PdfPTable(2);
        coverTable.setWidthPercentage(85);
        coverTable.setWidths(new float[]{1f, 2f});
        coverTable.setHorizontalAlignment(Element.ALIGN_CENTER);

        addCoverRow(coverTable, "Title", text(summary.title()));
        addCoverRow(coverTable, "Status", text(summary.status()));
        addCoverRow(coverTable, "Sponsor", text(summary.sponsor()));
        addCoverRow(coverTable, "Principal Investigator", text(summary.principalInvestigator()));
        addCoverRow(coverTable, "Lead Unit", text(summary.leadUnit()));
        addCoverRow(coverTable, "Current Sequence", text(summary.sequenceNumber()));
        addCoverRow(coverTable, "Document Number", text(summary.documentNumber()));
        addCoverRow(coverTable, "Generated", GENERATED_AT_FORMAT.format(data.generatedAt()));

        document.add(coverTable);

        Paragraph noticeSpacer = new Paragraph(" ");
        noticeSpacer.setSpacingAfter(50);
        document.add(noticeSpacer);

        Paragraph notice = new Paragraph(
                "Read-only legacy research administration archive. "
                        + "This report reflects archived Kuali Research "
                        + "Administration data preserved after the legacy "
                        + "system's retirement and is not a live system of "
                        + "record.",
                COVER_NOTICE
        );
        notice.setAlignment(Element.ALIGN_CENTER);
        document.add(notice);
    }

    private void addCoverRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, COVER_LABEL));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPaddingBottom(10);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, COVER_VALUE));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setPaddingBottom(10);
        table.addCell(valueCell);
    }

    private void addExecutiveSummary(Document document, AwardReportData data) throws DocumentException {
        document.newPage();
        AwardSummaryResponse s = data.summary();
        sectionHeading(document, "Executive Record Summary");

        PdfPTable table = twoColumnTable();
        addFieldRow(table, "Award Number", text(s.awardNumber()));
        addFieldRow(table, "Sequence Number", text(s.sequenceNumber()));
        addFieldRow(table, "Document Number", text(s.documentNumber()));
        addFieldRow(table, "Title", text(s.title()));
        addFieldRow(table, "Status", text(s.status()));
        addFieldRow(table, "Sponsor", text(s.sponsor()));
        addFieldRow(table, "Prime Sponsor", text(s.primeSponsor()));
        addFieldRow(table, "Principal Investigator", text(s.principalInvestigator()));
        addFieldRow(table, "Lead Unit", text(s.leadUnit()));
        addFieldRow(table, "Award Effective Date", formatDate(s.awardEffectiveDate()));
        addFieldRow(table, "Award Execution Date", formatDate(s.awardExecutionDate()));
        addFieldRow(table, "Begin Date", formatDate(s.beginDate()));
        addFieldRow(table, "Closeout Date", formatDate(s.closeoutDate()));
        addFieldRow(table, "Obligated Total Amount", formatCurrency(s.obligatedTotalAmount()));
        addFieldRow(table, "Anticipated Total Amount", formatCurrency(s.anticipatedTotalAmount()));
        addFieldRow(table, "Basis of Payment", codeAndDescription(s.basisOfPaymentCode(), s.basisOfPaymentDescription()));
        addFieldRow(table, "Method of Payment", codeAndDescription(s.methodOfPaymentCode(), s.methodOfPaymentDescription()));
        addFieldRow(table, "Root Award Number", text(s.rootAwardNumber()));
        addFieldRow(table, "Parent Award Number", text(s.parentAwardNumber()));
        addFieldRow(table, "Current Version", s.primaryCurrent() ? "Yes" : "No");
        document.add(table);
    }

    private void addVersionHistory(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Complete Version History");
        List<AwardVersionSummaryResponse> versions = data.versions();
        if (versions.isEmpty()) {
            emptyState(document);
            return;
        }

        long selectedAwardId = data.summary().awardId();
        Paragraph caption = new Paragraph(
                "Every archived version in this Award's family, in deterministic "
                        + "sequence order. The \"Selected?\" column marks the exact "
                        + "version this report was generated for (award_id "
                        + selectedAwardId + "); \"Current?\" marks the version "
                        + "currently active in the archive - these are not always "
                        + "the same row.",
                COVER_NOTICE
        );
        caption.setSpacingAfter(8);
        document.add(caption);

        PdfPTable table = dataTable(new float[]{1f, 1.2f, 1.5f, 1.3f, 1.4f, 1.2f, 1.1f, 0.9f, 0.9f});
        addHeaderRow(table, "Award ID", "Seq #", "Status", "Transaction Type", "Effective Date",
                "Document #", "Modification #", "Selected?", "Current?");
        boolean alt = false;
        for (AwardVersionSummaryResponse v : versions) {
            addRow(table, alt,
                    text(v.awardId()), text(v.sequenceNumber()), text(v.status()),
                    text(v.transactionType()), formatDate(v.awardEffectiveDate()),
                    text(v.documentNumber()), text(v.modificationNumber()),
                    v.awardId() != null && v.awardId() == selectedAwardId ? "Yes" : "No",
                    v.primaryCurrent() ? "Yes" : "No");
            alt = !alt;
        }
        document.add(table);
    }

    private void addPeopleAndUnits(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "People and Units");
        singleVersionScopeCaption(document, data);
        List<AwardPersonDetailResponse> people = data.people();
        if (people.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable table = dataTable(new float[]{1.6f, 1.1f, 1.3f, 0.8f, 0.9f, 0.9f, 0.9f, 2f});
        addHeaderRow(table, "Name", "Contact Role", "Key Person Role", "Lead PI?",
                "Acad. Yr Effort", "Cal. Yr Effort", "Total Effort", "Units");
        boolean alt = false;
        for (AwardPersonDetailResponse p : people) {
            StringBuilder units = new StringBuilder();
            for (AwardPersonUnitResponse u : p.units()) {
                if (units.length() > 0) {
                    units.append("; ");
                }
                units.append(text(u.unitNumber()));
                if (u.leadUnit()) {
                    units.append(" (lead)");
                }
            }
            addRow(table, alt,
                    text(p.fullName()), text(p.contactRoleCode()), text(p.keyPersonProjectRole()),
                    p.leadPrincipalInvestigator() ? "Yes" : "No",
                    formatPercent(p.academicYearEffort()), formatPercent(p.calendarYearEffort()),
                    formatPercent(p.totalEffort()), units.length() == 0 ? EM_DASH : units.toString());
            alt = !alt;
        }
        document.add(table);
    }

    private void addFundingProposals(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Funding Proposals");
        familyWideScopeCaption(document);
        List<AwardFundingProposalResponse> proposals = data.fundingProposals();
        if (proposals.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable table = dataTable(new float[]{0.8f, 1.1f, 2f, 1f, 1f, 1.4f, 1.2f, 1f});
        addHeaderRow(table, "Award ID", "Proposal #", "Title", "Status", "Document #",
                "Principal Investigator", "Sponsor", "Requested Total");
        boolean alt = false;
        for (AwardFundingProposalResponse p : proposals) {
            addRow(table, alt,
                    text(p.awardId()), text(p.proposalNumber()), text(p.proposalTitle()), text(p.proposalStatus()),
                    text(p.workflowDocumentNumber()), text(p.principalInvestigatorName()),
                    text(p.sponsorName()), formatCurrency(p.requestedTotalCost()));
            alt = !alt;
        }
        document.add(table);
    }

    private void addRelatedSubawards(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Related Subawards");
        noPerRowVersionIdentifierCaption(document);
        List<AwardFundingSubawardResponse> subawards = data.fundingSubawards();
        if (subawards.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable table = dataTable(new float[]{1.3f, 1.2f, 1.1f, 1.3f, 1.3f});
        addHeaderRow(table, "Subaward Code", "Organization ID", "Status", "Document #", "Amount");
        boolean alt = false;
        for (AwardFundingSubawardResponse sa : subawards) {
            addRow(table, alt,
                    text(sa.subawardCode()), text(sa.organizationId()), text(sa.subawardStatus()),
                    text(sa.workflowDocumentNumber()), formatCurrency(sa.subawardAmount()));
            alt = !alt;
        }
        document.add(table);
    }

    private void addRelatedNegotiations(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Related Negotiations");
        noPerRowVersionIdentifierCaption(document);
        List<AwardAssociatedNegotiationResponse> negotiations = data.associatedNegotiations();
        if (negotiations.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable table = dataTable(new float[]{1.3f, 1.2f, 1.4f, 1.4f, 1f, 1f});
        addHeaderRow(table, "Document #", "Status", "Agreement Type", "Negotiator", "Start Date", "End Date");
        boolean alt = false;
        for (AwardAssociatedNegotiationResponse n : negotiations) {
            addRow(table, alt,
                    text(n.documentNumber()), text(n.negotiationStatusDescription()),
                    text(n.negotiationAgreementTypeDescription()), text(n.negotiatorFullName()),
                    formatDate(n.negotiationStartDate()), formatDate(n.negotiationEndDate()));
            alt = !alt;
        }
        document.add(table);
    }

    private void addAmountHistory(Document document, AwardReportData data) throws DocumentException {
        newLandscapeSection(document, "Amount History");
        familyWideScopeCaption(document);
        List<AwardAmountHistoryResponse> amounts = data.amounts();
        if (amounts.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable table = dataTable(new float[]{0.8f, 0.7f, 1f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1f, 1.1f});
        addHeaderRow(table, "Award ID", "Seq #", "Effective Date", "Obligated Direct", "Obligated Indirect",
                "Obligated Total", "Anticipated Chg. Direct", "Anticipated Chg. Indirect",
                "Anticipated Total Direct", "Anticipated Total Indirect", "Anticipated Total",
                "Document #");
        boolean alt = false;
        for (AwardAmountHistoryResponse a : amounts) {
            addRow(table, alt,
                    text(a.awardId()), text(a.sequenceNumber()), formatDate(a.awardEffectiveDate()),
                    formatCurrency(a.obligatedTotalDirect()), formatCurrency(a.obligatedTotalIndirect()),
                    formatCurrency(a.obligatedTotalAmount()), formatCurrency(a.anticipatedChangeDirect()),
                    formatCurrency(a.anticipatedChangeIndirect()), formatCurrency(a.anticipatedTotalDirect()),
                    formatCurrency(a.anticipatedTotalIndirect()), formatCurrency(a.anticipatedTotalAmount()),
                    text(a.documentNumber()));
            alt = !alt;
        }
        document.add(table);
    }

    private void addBudgetData(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Budget Data");
        Paragraph budgetScopeNote = new Paragraph(
                "Budget Versions below span every Budget across the Award family with "
                        + "a sequence_number less than or equal to the selected version's "
                        + "own sequence_number (" + text(data.summary().sequenceNumber())
                        + ") — see docs/kuali-business-rules/Budget.md. Budget Periods, "
                        + "Line Items, and Personnel below all belong to the single Budget "
                        + "marked \"Selected? = Yes\" in the Budget Versions table.",
                COVER_NOTICE
        );
        budgetScopeNote.setSpacingAfter(8);
        document.add(budgetScopeNote);

        var summary = data.budgetSummary();
        if (summary.selectedBudgetId() == null) {
            subsectionHeading(document, "Selected Budget Summary");
            emptyState(document);
        } else {
            subsectionHeading(document, "Selected Budget Summary");
            PdfPTable summaryTable = twoColumnTable();
            addFieldRow(summaryTable, "Owning Award ID", text(summary.awardId()));
            addFieldRow(summaryTable, "Owning Award Number", text(summary.awardNumber()));
            addFieldRow(summaryTable, "Budget Version", text(summary.selectedBudgetVersionNumber()));
            addFieldRow(summaryTable, "Status", codeAndDescription(summary.statusCode(), summary.statusDescription()));
            addFieldRow(summaryTable, "Budget Document Number", text(summary.workflowDocumentNumber()));
            addFieldRow(summaryTable, "Start Date", formatDate(summary.startDate()));
            addFieldRow(summaryTable, "End Date", formatDate(summary.endDate()));
            addFieldRow(summaryTable, "Total Direct Cost", formatCurrency(summary.totalDirectCost()));
            addFieldRow(summaryTable, "Total Indirect Cost", formatCurrency(summary.totalIndirectCost()));
            addFieldRow(summaryTable, "Total Cost", formatCurrency(summary.totalCost()));
            addFieldRow(summaryTable, "Award Budget Total Cost Limit", formatCurrency(summary.awardBudgetTotalCostLimit()));
            addFieldRow(summaryTable, "Budget Change Total Cost Limit", formatCurrency(summary.budgetChangeTotalCostLimit()));
            document.add(summaryTable);
        }

        subsectionHeading(document, "Budget Versions");
        List<AwardBudgetVersionResponse> versions = data.budgetVersions();
        if (versions.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{0.8f, 0.8f, 1f, 1.3f, 1.3f, 1f, 1f, 1.1f, 1.1f, 0.9f});
            addHeaderRow(table, "Budget Ver.", "Owning Award ID", "Owning Seq #", "Status",
                    "Budget Document #", "Start Date", "End Date", "Total Direct", "Total Indirect", "Selected?");
            boolean alt = false;
            for (AwardBudgetVersionResponse v : versions) {
                addRow(table, alt,
                        text(v.budgetVersionNumber()), text(v.owningAwardId()), text(v.owningAwardSequenceNumber()),
                        codeAndDescription(v.statusCode(), v.statusDescription()), text(v.workflowDocumentNumber()),
                        formatDate(v.startDate()), formatDate(v.endDate()),
                        formatCurrency(v.totalDirectCost()), formatCurrency(v.totalIndirectCost()),
                        v.selected() ? "Yes" : "No");
                alt = !alt;
            }
            document.add(table);
        }

        subsectionHeading(document, "Budget Periods");
        List<AwardBudgetPeriodResponse> periods = data.budgetPeriods();
        if (periods.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{0.8f, 1.1f, 1.1f, 1.3f, 1.3f, 1.3f});
            addHeaderRow(table, "Period #", "Start Date", "End Date", "Total Direct", "Total Indirect", "Total Cost");
            boolean alt = false;
            for (AwardBudgetPeriodResponse p : periods) {
                addRow(table, alt,
                        text(p.periodNumber()), formatDate(p.startDate()), formatDate(p.endDate()),
                        formatCurrency(p.totalDirectCost()), formatCurrency(p.totalIndirectCost()),
                        formatCurrency(p.totalCost()));
                alt = !alt;
            }
            document.add(table);
        }

        subsectionHeading(document, "Budget Line Items");
        List<AwardBudgetLineItemResponse> lineItems = data.budgetLineItems();
        if (lineItems.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{0.7f, 2.2f, 1.1f, 1f, 1f, 1.2f, 1.2f});
            addHeaderRow(table, "Line #", "Description", "Cost Element", "Start Date", "End Date",
                    "Line Item Cost", "Cost Sharing");
            boolean alt = false;
            for (AwardBudgetLineItemResponse li : lineItems) {
                addRow(table, alt,
                        text(li.lineItemNumber()), text(li.description()), text(li.costElement()),
                        formatDate(li.startDate()), formatDate(li.endDate()),
                        formatCurrency(li.lineItemCost()), formatCurrency(li.costSharingAmount()));
                alt = !alt;
            }
            document.add(table);
        }

        subsectionHeading(document, "Budget Personnel");
        List<AwardBudgetPersonnelResponse> personnel = data.budgetPersonnel();
        if (personnel.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{1.8f, 1.1f, 1.3f, 1.2f, 1.2f});
            addHeaderRow(table, "Name", "Job Code", "Appointment Type", "Base Salary", "Calculated Salary");
            boolean alt = false;
            for (AwardBudgetPersonnelResponse p : personnel) {
                addRow(table, alt,
                        text(p.fullName()), text(p.jobCode()), text(p.appointmentType()),
                        formatCurrency(p.baseSalary()), formatCurrency(p.calculatedSalary()));
                alt = !alt;
            }
            document.add(table);
        }
    }

    private void addTimeAndMoney(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Time and Money History");

        var summary = data.timeAndMoneySummary();
        subsectionHeading(document, "Summary");
        singleVersionScopeCaption(document, data);
        PdfPTable summaryTable = twoColumnTable();
        addFieldRow(summaryTable, "Award ID", text(summary.awardId()));
        addFieldRow(summaryTable, "Sequence Number", text(summary.sequenceNumber()));
        addFieldRow(summaryTable, "Obligated Total Amount", formatCurrency(summary.obligatedTotalAmount()));
        addFieldRow(summaryTable, "Anticipated Total Amount", formatCurrency(summary.anticipatedTotalAmount()));
        addFieldRow(summaryTable, "Family Transaction Count", text(summary.familyTransactionCount()));
        addFieldRow(summaryTable, "Last Family Document #", text(summary.lastFamilyTimeAndMoneyDocumentNumber()));
        addFieldRow(summaryTable, "Last Family Notice Date", formatDate(summary.lastFamilyNoticeDate()));
        addFieldRow(summaryTable, "Last Family Transaction Type", text(summary.lastFamilyTransactionTypeDescription()));
        document.add(summaryTable);

        subsectionHeading(document, "Actions");
        noPerRowVersionIdentifierCaption(document);
        List<TimeAndMoneyActionResponse> actions = data.timeAndMoneyActions();
        if (actions.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{1.4f, 1.2f, 1.5f, 1.1f, 1.1f, 1.8f});
            addHeaderRow(table, "Document #", "Notice Date", "Transaction Type", "Status", "Update Timestamp", "Comments");
            boolean alt = false;
            for (TimeAndMoneyActionResponse a : actions) {
                addRow(table, alt,
                        text(a.timeAndMoneyDocumentNumber()), formatDate(a.noticeDate()),
                        text(a.transactionTypeDescription()), text(a.documentStatus()),
                        formatDateTime(a.sourceUpdateTimestamp()), truncatedText(a.comments()));
                alt = !alt;
            }
            document.add(table);
        }

        subsectionHeading(document, "History");
        familyWideScopeCaption(document);
        List<TimeAndMoneyHistoryEntryResponse> history = data.timeAndMoneyHistory();
        if (history.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{0.8f, 0.7f, 1.4f, 1.1f, 1.2f, 1.2f, 1.2f, 1.2f});
            addHeaderRow(table, "Award ID", "Seq #", "Document #", "Effective Date", "Obligated Total",
                    "Anticipated Total", "Originating Ver.", "Created?");
            boolean alt = false;
            for (TimeAndMoneyHistoryEntryResponse h : history) {
                addRow(table, alt,
                        text(h.awardId()), text(h.sequenceNumber()), text(h.timeAndMoneyDocumentNumber()),
                        formatDate(h.awardEffectiveDate()), formatCurrency(h.obligatedTotalAmount()),
                        formatCurrency(h.anticipatedTotalAmount()), text(h.originatingAwardVersion()),
                        h.timeAndMoneyCreated() ? "Yes" : "No");
                alt = !alt;
            }
            document.add(table);
        }
    }

    private void addTerms(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Terms and Report Requirements");
        singleVersionScopeCaption(document, data);
        var terms = data.terms();

        subsectionHeading(document, "Sponsor Terms");
        List<AwardSponsorTermResponse> sponsorTerms = terms.sponsorTerms();
        if (sponsorTerms.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{1f, 2.4f, 1.2f, 1.6f});
            addHeaderRow(table, "Term Code", "Description", "Term Type", "Category");
            boolean alt = false;
            for (AwardSponsorTermResponse st : sponsorTerms) {
                addRow(table, alt,
                        text(st.sponsorTermCode()), text(st.description()),
                        text(st.sponsorTermTypeCode()), text(st.categoryDescription()));
                alt = !alt;
            }
            document.add(table);
        }

        subsectionHeading(document, "Report Terms");
        List<AwardReportTermResponse> reportTerms = terms.reportTerms();
        if (reportTerms.isEmpty()) {
            emptyState(document);
        } else {
            PdfPTable table = dataTable(new float[]{1.3f, 1.5f, 1.4f, 1f, 1.1f, 0.9f});
            addHeaderRow(table, "Report Code", "Description", "Frequency", "Due Date", "Distribution", "Recipients");
            boolean alt = false;
            for (AwardReportTermResponse rt : reportTerms) {
                addRow(table, alt,
                        text(rt.reportCode()), text(rt.reportDescription()),
                        codeAndDescription(rt.frequencyCode(), rt.frequencyDescription()),
                        formatDate(rt.dueDate()), text(rt.distributionDescription()),
                        text(rt.recipientCount()));
                alt = !alt;
            }
            document.add(table);
        }
    }

    private void addCustomData(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Custom Data");
        singleVersionScopeCaption(document, data);
        List<AwardCustomDataResponse> customData = data.customData();
        if (customData.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable table = dataTable(new float[]{1.4f, 1.3f, 1.5f, 2.2f, 1.5f});
        addHeaderRow(table, "Label", "Data Type", "Group", "Value", "Source Update");
        boolean alt = false;
        for (AwardCustomDataResponse cd : customData) {
            String label = cd.label() != null ? cd.label() : cd.name();
            addRow(table, alt,
                    text(label), text(cd.dataType()), text(cd.groupName()),
                    truncatedText(cd.value()), formatDateTime(cd.sourceUpdateTimestamp()));
            alt = !alt;
        }
        document.add(table);
    }

    private void addCommentsAndNotepad(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Comments and Notepad");
        var comments = data.comments();

        subsectionHeading(document, "Comments");
        List<AwardCommentCategoryResponse> categories = comments.commentCategories();
        if (categories.isEmpty()) {
            emptyState(document);
        } else {
            familyWideScopeCaption(document);
            for (AwardCommentCategoryResponse category : categories) {
                Paragraph categoryHeading = new Paragraph(text(category.commentTypeDescription()), SUBSECTION_HEADING);
                categoryHeading.setSpacingBefore(10);
                categoryHeading.setSpacingAfter(4);
                document.add(categoryHeading);

                PdfPTable table = dataTable(new float[]{0.8f, 0.7f, 1.4f, 1.3f, 3f, 1.3f});
                addHeaderRow(table, "Award ID", "Seq #", "Document #", "Updated", "Comment", "Updated By");
                boolean alt = false;
                for (var entry : category.history()) {
                    addRow(table, alt,
                            text(entry.awardId()), text(entry.sequenceNumber()),
                            text(entry.workflowDocumentNumber()), formatDateTime(entry.updateTimestamp()),
                            truncatedText(entry.commentText()), text(entry.updateUser()));
                    alt = !alt;
                }
                document.add(table);
            }
        }

        subsectionHeading(document, "Notepad Entries");
        List<AwardNotepadEntryResponse> notepad = comments.notepadEntries();
        if (notepad.isEmpty()) {
            emptyState(document);
        } else {
            noPerRowVersionIdentifierCaption(document);
            PdfPTable table = dataTable(new float[]{1.6f, 3f, 1.5f, 1.5f});
            addHeaderRow(table, "Topic", "Comments", "Created", "Updated");
            boolean alt = false;
            for (AwardNotepadEntryResponse n : notepad) {
                addRow(table, alt,
                        text(n.noteTopic()), truncatedText(n.comments()),
                        formatDateTime(n.sourceCreateTimestamp()), formatDateTime(n.sourceUpdateTimestamp()));
                alt = !alt;
            }
            document.add(table);
        }
    }

    private void addSapTransmissionHistory(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "SAP Transmission History");
        singleVersionScopeCaption(document, data);
        List<AwardSapTransmissionResponse> transmissions = data.sapTransmissions();
        if (transmissions.isEmpty()) {
            emptyState(document);
            return;
        }

        PdfPTable summaryTable = dataTable(new float[]{0.9f, 1.1f, 1.3f, 1f, 1.4f, 1.2f});
        addHeaderRow(summaryTable, "Seq #", "Transmission Date", "Document #", "Successful?",
                "Initiator", "Transmitter");
        boolean alt = false;
        for (AwardSapTransmissionResponse t : transmissions) {
            addRow(summaryTable, alt,
                    text(t.sequenceNumber()), formatDate(t.transmissionDate()), text(t.documentNumber()),
                    t.successful() ? "Yes" : "No", text(t.initiatorId()), text(t.transmitterId()));
            alt = !alt;
        }
        document.add(summaryTable);

        for (AwardSapTransmissionResponse t : transmissions) {
            Paragraph heading = new Paragraph(
                    "Transmission " + text(t.documentNumber()) + " (" + formatDate(t.transmissionDate()) + ")",
                    SUBSECTION_HEADING
            );
            heading.setSpacingBefore(12);
            heading.setSpacingAfter(4);
            document.add(heading);
            addXmlBlock(document, "Sent Data", t.sentData());
            addXmlBlock(document, "Returned Data", t.returnedData());
        }
    }

    private void addXmlBlock(Document document, String label, String xml) throws DocumentException {
        Paragraph labelParagraph = new Paragraph(label, SUBSECTION_HEADING);
        labelParagraph.setSpacingBefore(6);
        labelParagraph.setSpacingAfter(2);
        document.add(labelParagraph);

        String value = truncatedText(xml);
        Paragraph body = new Paragraph(value.equals(EM_DASH) ? "Not recorded." : value, MONOSPACE_FONT);
        body.setSpacingAfter(6);
        document.add(body);
    }

    private void addSourceReferenceAppendix(Document document, AwardReportData data) throws DocumentException {
        newSection(document, "Source-Reference Appendix");
        Paragraph intro = new Paragraph(
                "Every award_id, sequence_number, and document_number referenced by this "
                        + "report, for traceability back to the archived source records.",
                COVER_NOTICE
        );
        intro.setSpacingAfter(10);
        document.add(intro);

        PdfPTable table = dataTable(new float[]{1.4f, 1f, 1.6f, 2f});
        addHeaderRow(table, "Award ID", "Seq #", "Document #", "Source");
        boolean alt = false;
        for (AwardVersionSummaryResponse v : data.versions()) {
            addRow(table, alt, text(v.awardId()), text(v.sequenceNumber()), text(v.documentNumber()), "Version History");
            alt = !alt;
        }
        for (AwardSapTransmissionResponse t : data.sapTransmissions()) {
            addRow(table, alt, EM_DASH, text(t.sequenceNumber()), text(t.documentNumber()), "SAP Transmission");
            alt = !alt;
        }
        document.add(table);
    }

    // --- layout helpers ---

    private void newSection(Document document, String heading) throws DocumentException {
        document.setPageSize(PageSize.LETTER);
        document.newPage();
        sectionHeading(document, heading);
    }

    private void newLandscapeSection(Document document, String heading) throws DocumentException {
        document.setPageSize(PageSize.LETTER.rotate());
        document.newPage();
        sectionHeading(document, heading);
    }

    private void sectionHeading(Document document, String text) throws DocumentException {
        Paragraph heading = new Paragraph(text, SECTION_HEADING);
        heading.setSpacingAfter(12);
        document.add(heading);
    }

    private void subsectionHeading(Document document, String text) throws DocumentException {
        Paragraph heading = new Paragraph(text, SUBSECTION_HEADING);
        heading.setSpacingBefore(14);
        heading.setSpacingAfter(6);
        document.add(heading);
    }

    private void emptyState(Document document) throws DocumentException {
        Paragraph paragraph = new Paragraph("No archived data available.", EMPTY_STATE_FONT);
        paragraph.setSpacingAfter(10);
        document.add(paragraph);
    }

    /*
     * Every section must make its own scope unambiguous - these three
     * captions are the only three scopes any section in this report
     * ever has: (1) exactly the archived version the report was
     * generated for, (2) the whole Award family with every row
     * individually labeled by award_id/sequence_number, or (3) a
     * family-level relationship the archive itself never attributes to
     * one specific Award version. Never state (2) without the calling
     * method also rendering per-row Award ID/Seq # columns.
     */
    private void singleVersionScopeCaption(Document document, AwardReportData data) throws DocumentException {
        AwardSummaryResponse s = data.summary();
        Paragraph caption = new Paragraph(
                "Shown for the selected Award version only — award_id "
                        + text(s.awardId()) + " · sequence " + text(s.sequenceNumber())
                        + " · document " + text(s.documentNumber()) + ".",
                COVER_NOTICE
        );
        caption.setSpacingAfter(8);
        document.add(caption);
    }

    private void familyWideScopeCaption(Document document) throws DocumentException {
        Paragraph caption = new Paragraph(
                "Shown for the entire Award family (every archived version that "
                        + "has a row) — each row below is labeled with its own "
                        + "award_id and sequence_number.",
                COVER_NOTICE
        );
        caption.setSpacingAfter(8);
        document.add(caption);
    }

    private void noPerRowVersionIdentifierCaption(Document document) throws DocumentException {
        Paragraph caption = new Paragraph(
                "This relationship is recorded at the Award family level in the "
                        + "archive — the source data does not attribute individual "
                        + "rows below to one specific archived Award version.",
                COVER_NOTICE
        );
        caption.setSpacingAfter(8);
        document.add(caption);
    }

    private PdfPTable twoColumnTable() {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1f, 2f});
        table.setSpacingAfter(14);
        return table;
    }

    private void addFieldRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, FIELD_LABEL_FONT));
        labelCell.setBorder(Rectangle.BOTTOM);
        labelCell.setBorderColor(TABLE_BORDER);
        labelCell.setPadding(5);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, TABLE_BODY_FONT));
        valueCell.setBorder(Rectangle.BOTTOM);
        valueCell.setBorderColor(TABLE_BORDER);
        valueCell.setPadding(5);
        table.addCell(valueCell);
    }

    private PdfPTable dataTable(float[] widths) {
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        table.setWidths(widths);
        table.setHeaderRows(1);
        table.setSpacingBefore(4);
        table.setSpacingAfter(14);
        table.setSplitLate(false);
        table.setKeepTogether(false);
        return table;
    }

    private void addHeaderRow(PdfPTable table, String... headers) {
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, TABLE_HEADER_FONT));
            cell.setBackgroundColor(TABLE_HEADER_BG);
            cell.setPadding(5);
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.addCell(cell);
        }
    }

    private void addRow(PdfPTable table, boolean alt, String... values) {
        for (String value : values) {
            PdfPCell cell = new PdfPCell(new Phrase(value, TABLE_BODY_FONT));
            cell.setPadding(4);
            cell.setBorderColor(TABLE_BORDER);
            if (alt) {
                cell.setBackgroundColor(TABLE_ALT_ROW);
            }
            table.addCell(cell);
        }
    }

    // --- formatting helpers ---

    private static final String EM_DASH = "—";

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return EM_DASH;
        }
        // Defensive: strip control characters (other than tab/newline)
        // that could otherwise distort layout - the value is still
        // always added as literal Chunk/Phrase text, never parsed as
        // markup, regardless of this step.
        return value.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
    }

    private static String text(Object value) {
        return value == null ? EM_DASH : text(String.valueOf(value));
    }

    private static String truncatedText(String value) {
        String sanitized = text(value);
        if (sanitized.equals(EM_DASH) || sanitized.length() <= FREE_TEXT_TRUNCATE_LIMIT) {
            return sanitized;
        }
        return sanitized.substring(0, FREE_TEXT_TRUNCATE_LIMIT)
                + "\n[truncated, " + sanitized.length() + " characters total]";
    }

    private static String codeAndDescription(String code, String description) {
        if ((code == null || code.isBlank()) && (description == null || description.isBlank())) {
            return EM_DASH;
        }
        if (description == null || description.isBlank()) {
            return text(code);
        }
        if (code == null || code.isBlank()) {
            return text(description);
        }
        return text(code) + " - " + text(description);
    }

    private static String formatDate(LocalDate date) {
        return date == null ? EM_DASH : DATE_FORMAT.format(date);
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? EM_DASH : DATE_TIME_FORMAT.format(dateTime);
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return EM_DASH;
        }
        boolean negative = amount.signum() < 0;
        String formatted = String.format(Locale.US, "$%,.2f", amount.abs());
        return negative ? "(" + formatted + ")" : formatted;
    }

    // Person-effort fields (academicYearEffort/calendarYearEffort/
    // totalEffort) are percentages, not currency - matches the UI's
    // own formatEffortNote in awardSectionsPresentation.mjs.
    private static String formatPercent(BigDecimal value) {
        if (value == null) {
            return EM_DASH;
        }
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    /*
     * Footer shown on every page: Award number (traceability if pages
     * are separated/printed) and a running page number. Deliberately
     * omits a "of N" total-page-count to avoid OpenPDF's fragile
     * deferred-template idiom for a cosmetic-only feature.
     */
    private static final class ReportFooterEvent extends PdfPageEventHelper {
        private final String awardNumber;

        private ReportFooterEvent(String awardNumber) {
            this.awardNumber = awardNumber == null ? "" : awardNumber;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            Phrase footer = new Phrase(
                    "Award " + awardNumber + "  ·  Page " + writer.getPageNumber(),
                    FOOTER_FONT
            );
            ColumnText.showTextAligned(
                    canvas,
                    Element.ALIGN_CENTER,
                    footer,
                    (document.right() + document.left()) / 2,
                    document.bottom() - 24,
                    0
            );
        }
    }
}
