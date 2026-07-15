package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.out.persistence.entity.IrbSearchEntity;
import edu.bu.archive.domain.model.IrbProtocol;

final class IrbPersistenceMapper {

    private IrbPersistenceMapper() {
    }

    static IrbProtocol toDomain(IrbSearchEntity entity) {
        return new IrbProtocol(
                entity.getRecordId(),
                entity.getStudyId(),
                entity.getProtocolBase(),
                entity.getProtocolNumber(),
                entity.getTitle(),
                entity.getProtocolType(),
                entity.getProtocolStatus(),
                entity.getApprovalDate(),
                entity.getPiBuid(),
                entity.getPiFirstName(),
                entity.getPiLastName(),
                entity.getPiFullName(),
                entity.getPiEmail(),
                entity.isPiBuidMissing(),
                entity.isActiveFlag(),
                entity.getLoadedAt()
        );
    }
}
