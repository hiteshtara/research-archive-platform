package edu.bu.archive.adapter.out.persistence.repository;

import edu.bu.archive.adapter.out.persistence.entity.IrbSearchEntity;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IrbSearchJpaRepository
        extends JpaRepository<IrbSearchEntity, Long>,
                JpaSpecificationExecutor<IrbSearchEntity> {

    Optional<IrbSearchEntity> findByStudyIdIgnoreCase(String studyId);
}
