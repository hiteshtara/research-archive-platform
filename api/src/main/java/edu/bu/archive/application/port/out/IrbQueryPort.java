package edu.bu.archive.application.port.out;

import edu.bu.archive.domain.model.IrbProtocol;
import edu.bu.archive.domain.model.IrbSearchQuery;
import edu.bu.archive.domain.model.PageResult;

import java.util.Optional;

public interface IrbQueryPort {

    PageResult<IrbProtocol> search(IrbSearchQuery query);

    Optional<IrbProtocol> findByStudyId(String studyId);

    Optional<IrbProtocol> findByRecordId(Long recordId);

    long count();
}
