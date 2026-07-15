package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.out.persistence.entity.IrbSearchEntity;
import edu.bu.archive.adapter.out.persistence.repository.IrbSearchJpaRepository;
import edu.bu.archive.application.port.out.IrbQueryPort;
import edu.bu.archive.domain.model.IrbProtocol;
import edu.bu.archive.domain.model.IrbSearchQuery;
import edu.bu.archive.domain.model.PageResult;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class IrbPersistenceAdapter implements IrbQueryPort {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "studyId",
            "protocolNumber",
            "title",
            "protocolType",
            "protocolStatus",
            "approvalDate",
            "piFullName",
            "loadedAt"
    );

    private final IrbSearchJpaRepository repository;

    public IrbPersistenceAdapter(IrbSearchJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<IrbProtocol> search(IrbSearchQuery query) {
        String sortField = ALLOWED_SORT_FIELDS.contains(query.sort())
                ? query.sort()
                : "approvalDate";

        Sort.Direction direction = "asc".equals(query.direction())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        PageRequest pageRequest = PageRequest.of(
                query.page(),
                query.size(),
                Sort.by(direction, sortField)
        );

        Page<IrbSearchEntity> result = repository.findAll(
                buildSpecification(query),
                pageRequest
        );

        return new PageResult<>(
                result.getContent()
                        .stream()
                        .map(IrbPersistenceMapper::toDomain)
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast()
        );
    }

    @Override
    public Optional<IrbProtocol> findByStudyId(String studyId) {
        return repository.findByStudyIdIgnoreCase(studyId)
                .map(IrbPersistenceMapper::toDomain);
    }

    @Override
    public long count() {
        return repository.count();
    }

    private Specification<IrbSearchEntity> buildSpecification(
            IrbSearchQuery query
    ) {
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(query.query())) {
                String pattern =
                        "%" + query.query().trim().toLowerCase() + "%";

                predicates.add(builder.or(
                        builder.like(
                                builder.lower(root.get("studyId")),
                                pattern
                        ),
                        builder.like(
                                builder.lower(root.get("protocolNumber")),
                                pattern
                        ),
                        builder.like(
                                builder.lower(root.get("protocolBase")),
                                pattern
                        ),
                        builder.like(
                                builder.lower(root.get("title")),
                                pattern
                        ),
                        builder.like(
                                builder.lower(root.get("piFullName")),
                                pattern
                        )
                ));
            }

            if (StringUtils.hasText(query.status())) {
                predicates.add(builder.equal(
                        builder.lower(root.get("protocolStatus")),
                        query.status().trim().toLowerCase()
                ));
            }

            if (StringUtils.hasText(query.type())) {
                predicates.add(builder.equal(
                        builder.lower(root.get("protocolType")),
                        query.type().trim().toLowerCase()
                ));
            }

            return builder.and(
                    predicates.toArray(Predicate[]::new)
            );
        };
    }
}
