package edu.bu.archive.application.service;

import edu.bu.archive.application.port.in.IrbQueryUseCase;
import edu.bu.archive.application.port.out.IrbQueryPort;
import edu.bu.archive.domain.model.IrbProtocol;
import edu.bu.archive.domain.model.IrbSearchQuery;
import edu.bu.archive.domain.model.PageResult;
import edu.bu.archive.exception.RecordNotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IrbQueryService implements IrbQueryUseCase {

    private final IrbQueryPort queryPort;

    public IrbQueryService(IrbQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override
    public PageResult<IrbProtocol> search(IrbSearchQuery query) {
        return queryPort.search(query);
    }

    @Override
    public IrbProtocol findByStudyId(String studyId) {
        if (studyId == null || studyId.isBlank()) {
            throw new IllegalArgumentException(
                    "Study ID cannot be blank."
            );
        }

        return queryPort.findByStudyId(studyId.trim())
                .orElseThrow(() -> new RecordNotFoundException(
                        "IRB study not found: " + studyId
                ));
    }

    @Override
    public IrbProtocol findByRecordId(Long recordId) {
        if (recordId == null || recordId < 1) {
            throw new IllegalArgumentException(
                    "Record ID must be a positive number."
            );
        }

        return queryPort.findByRecordId(recordId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "IRB record not found: " + recordId
                ));
    }

    @Override
    public long count() {
        return queryPort.count();
    }
}
