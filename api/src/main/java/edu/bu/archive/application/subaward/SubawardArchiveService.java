package edu.bu.archive.application.subaward;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAmountResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCloseoutResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardContactResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardFundingResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotepadResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardReportResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardTemplateInfoResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.SubawardArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SubawardArchiveService {

    private final SubawardArchiveRepository repository;

    public SubawardArchiveService(
            SubawardArchiveRepository repository
    ) {
        this.repository = repository;
    }

    public SubawardPageResponse findPage(
            String query,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long totalElements = repository.countSubawards(query);
        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / safeSize);
        int offset = safePage * safeSize;

        List<SubawardSummaryResponse> content =
                repository.findSubawards(
                        query,
                        safeSize,
                        offset
                );

        return new SubawardPageResponse(
                content,
                safePage,
                safeSize,
                totalElements,
                totalPages,
                safePage == 0,
                totalPages == 0 || safePage >= totalPages - 1
        );
    }

    public SubawardWorkspaceResponse findWorkspace(long subawardId) {
        SubawardRowResponse current = requireSubaward(subawardId);
        return new SubawardWorkspaceResponse(subawardId, current);
    }

    public List<SubawardAmountResponse> findAmounts(long subawardId) {
        requireSubaward(subawardId);
        return repository.findAmounts(subawardId);
    }

    public List<SubawardContactResponse> findContacts(long subawardId) {
        requireSubaward(subawardId);
        return repository.findContacts(subawardId);
    }

    public List<SubawardCustomDataResponse> findCustomData(long subawardId) {
        requireSubaward(subawardId);
        return repository.findCustomData(subawardId);
    }

    public List<SubawardFundingResponse> findFunding(long subawardId) {
        requireSubaward(subawardId);
        return repository.findFunding(subawardId);
    }

    public List<SubawardAttachmentResponse> findAttachments(long subawardId) {
        requireSubaward(subawardId);
        return repository.findAttachments(subawardId);
    }

    public SubawardTemplateInfoResponse findTemplateInfo(long subawardId) {
        requireSubaward(subawardId);
        return repository.findTemplateInfo(subawardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Subaward template info not found: "
                                        + subawardId
                        )
                );
    }

    public List<SubawardCloseoutResponse> findCloseout(long subawardId) {
        requireSubaward(subawardId);
        return repository.findCloseout(subawardId);
    }

    public List<SubawardReportResponse> findReports(long subawardId) {
        requireSubaward(subawardId);
        return repository.findReports(subawardId);
    }

    public List<SubawardNotepadResponse> findNotepad(long subawardId) {
        requireSubaward(subawardId);
        return repository.findNotepad(subawardId);
    }

    public List<SubawardNotificationResponse> findNotifications(
            long subawardId
    ) {
        requireSubaward(subawardId);
        return repository.findNotifications(subawardId);
    }

    private SubawardRowResponse requireSubaward(long subawardId) {
        if (subawardId <= 0) {
            throw new IllegalArgumentException(
                    "Subaward ID must be positive"
            );
        }

        return repository.findById(subawardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Subaward not found: " + subawardId
                        )
                );
    }
}
