package edu.bu.archive.application.port.in;

import edu.bu.archive.domain.model.IrbProtocol;
import edu.bu.archive.domain.model.IrbSearchQuery;
import edu.bu.archive.domain.model.PageResult;

public interface IrbQueryUseCase {

    PageResult<IrbProtocol> search(IrbSearchQuery query);

    IrbProtocol findByStudyId(String studyId);

    IrbProtocol findByRecordId(Long recordId);

    long count();
}
