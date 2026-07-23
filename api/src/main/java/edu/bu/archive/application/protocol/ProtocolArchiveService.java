package edu.bu.archive.application.protocol;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolPersonResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolVersionResponse;
import edu.bu.archive.adapter.out.persistence.ProtocolArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ProtocolArchiveService {

    private final ProtocolArchiveRepository repository;

    public ProtocolArchiveService(ProtocolArchiveRepository repository) {
        this.repository = repository;
    }

    public PageResponse<ProtocolSummaryResponse> findFamilies(
            String query,
            int page,
            int size
    ) {
        return repository.findFamilies(
                query,
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100)
        );
    }

    public List<ProtocolVersionResponse> findHistory(
            String protocolNumber
    ) {
        String normalized = normalize(protocolNumber);
        List<ProtocolVersionResponse> history =
                repository.findHistory(normalized);
        if (history.isEmpty()) {
            throw new NoSuchElementException(
                    "Protocol not found: " + normalized
            );
        }
        return history;
    }

    public ProtocolVersionResponse findVersion(long protocolId) {
        requireId(protocolId);
        return repository.findVersion(protocolId).orElseThrow(
                () -> new NoSuchElementException(
                        "Protocol version not found: " + protocolId
                )
        );
    }

    public List<ProtocolPersonResponse> findPersonnel(long protocolId) {
        findVersion(protocolId);
        return repository.findPersonnel(protocolId);
    }

    public List<ProtocolFundingResponse> findFunding(long protocolId) {
        findVersion(protocolId);
        return repository.findFunding(protocolId);
    }

    private String normalize(String protocolNumber) {
        String normalized = protocolNumber == null
                ? ""
                : protocolNumber.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Protocol number is required"
            );
        }
        return normalized;
    }

    private void requireId(long protocolId) {
        if (protocolId < 1) {
            throw new IllegalArgumentException(
                    "Protocol ID must be positive"
            );
        }
    }
}
