package edu.bu.archive.application.ai;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.domain.model.ai.AiCitation;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.ai.questions-enabled",
        havingValue = "true"
)
public class AwardDeterministicFactResolver {

    static final String INSUFFICIENT_ANSWER =
            "The archived records do not contain enough information "
                    + "to answer this question.";

    private final AwardArchiveService awardArchiveService;

    public AwardDeterministicFactResolver(
            AwardArchiveService awardArchiveService
    ) {
        this.awardArchiveService = awardArchiveService;
    }

    AwardFactResolution resolve(
            AwardQuestionIntent intent,
            AwardFamilyResponse family
    ) {
        AwardRowResponse current = family.current();
        List<AiCitation> citations =
                List.of(citation(current));
        return switch (intent) {
            case CURRENT_STATUS -> value(
                    current.status(),
                    "The current archived Award status is %s.",
                    citations
            );
            case CURRENT_SPONSOR -> sponsor(
                    current,
                    citations
            );
            case CURRENT_LEAD_UNIT -> value(
                    current.leadUnit(),
                    "The current archived lead unit is %s.",
                    citations
            );
            case CURRENT_PI -> principalInvestigators(
                    family.awardNumber(),
                    citations
            );
            case CURRENT_SEQUENCE -> sufficient(
                    "The current archived sequence is "
                            + current.sequenceNumber()
                            + ".",
                    citations
            );
            case CURRENT_TITLE -> value(
                    current.title(),
                    "The current archived Award title is %s.",
                    citations
            );
            case CURRENT_DATES -> dates(
                    current,
                    citations
            );
            case CURRENT_ANTICIPATED_AMOUNT -> amount(
                    family,
                    true,
                    citations
            );
            case CURRENT_OBLIGATED_AMOUNT -> amount(
                    family,
                    false,
                    citations
            );
            default -> insufficient();
        };
    }

    AwardFactResolution insufficient() {
        return new AwardFactResolution(
                INSUFFICIENT_ANSWER,
                List.of(),
                false
        );
    }

    private AwardFactResolution sponsor(
            AwardRowResponse current,
            List<AiCitation> citations
    ) {
        if (blank(current.sponsor())
                && blank(current.primeSponsor())) {
            return insufficient();
        }
        if (!blank(current.sponsor())
                && !blank(current.primeSponsor())) {
            return sufficient(
                    "The current archived sponsor is "
                            + current.sponsor()
                            + ", and the prime sponsor is "
                            + current.primeSponsor()
                            + ".",
                    citations
            );
        }
        String value = !blank(current.sponsor())
                ? "The current archived sponsor is "
                        + current.sponsor() + "."
                : "The current archived prime sponsor is "
                        + current.primeSponsor() + ".";
        return sufficient(value, citations);
    }

    private AwardFactResolution principalInvestigators(
            String awardNumber,
            List<AiCitation> citations
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        awardArchiveService.findCurrentPeople(awardNumber)
                .stream()
                .filter(this::isPrincipalInvestigator)
                .map(AwardPersonResponse::fullName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
        if (names.isEmpty()) {
            return insufficient();
        }
        return sufficient(
                names.size() == 1
                        ? "The current archived principal investigator "
                                + "is " + names.getFirst() + "."
                        : "The current archived principal investigators "
                                + "are " + String.join(", ", names) + ".",
                citations
        );
    }

    private boolean isPrincipalInvestigator(
            AwardPersonResponse person
    ) {
        return "PI".equalsIgnoreCase(person.contactRoleCode())
                || "PRINCIPAL INVESTIGATOR".equalsIgnoreCase(
                        person.keyPersonProjectRole()
                );
    }

    private AwardFactResolution dates(
            AwardRowResponse current,
            List<AiCitation> citations
    ) {
        if (current.beginDate() == null
                && current.closeoutDate() == null) {
            return insufficient();
        }
        if (current.beginDate() != null
                && current.closeoutDate() != null) {
            return sufficient(
                    "The archived begin date is "
                            + current.beginDate()
                            + ", and the archived closeout date is "
                            + current.closeoutDate()
                            + ".",
                    citations
            );
        }
        return sufficient(
                current.beginDate() != null
                        ? "The archived begin date is "
                                + current.beginDate() + "."
                        : "The archived closeout date is "
                                + current.closeoutDate() + ".",
                citations
        );
    }

    private AwardFactResolution amount(
            AwardFamilyResponse family,
            boolean anticipated,
            List<AiCitation> citations
    ) {
        AwardAmountResponse amount =
                awardArchiveService.findCurrentAmounts(
                                family.awardNumber()
                        )
                        .stream()
                        .filter(candidate -> Objects.equals(
                                candidate.awardId(),
                                family.current().awardId()
                        ))
                        .findFirst()
                        .orElse(null);
        BigDecimal value = amount == null
                ? null
                : anticipated
                        ? amount.anticipatedTotalAmount()
                        : amount.obligatedTotalAmount();
        if (value == null) {
            return insufficient();
        }
        String label = anticipated
                ? "anticipated total"
                : "obligated total";
        return sufficient(
                "The current archived " + label + " is "
                        + NumberFormat.getCurrencyInstance(Locale.US)
                                .format(value)
                        + ".",
                citations
        );
    }

    private AwardFactResolution value(
            String value,
            String template,
            List<AiCitation> citations
    ) {
        return blank(value)
                ? insufficient()
                : sufficient(
                        template.formatted(value),
                        citations
                );
    }

    private AwardFactResolution sufficient(
            String answer,
            List<AiCitation> citations
    ) {
        return new AwardFactResolution(
                answer,
                citations,
                true
        );
    }

    private AiCitation citation(
            AwardRowResponse row
    ) {
        return new AiCitation(
                "award",
                String.valueOf(row.awardId()),
                row.awardNumber(),
                row.sequenceNumber()
        );
    }

    private boolean blank(
            String value
    ) {
        return value == null || value.isBlank();
    }
}
