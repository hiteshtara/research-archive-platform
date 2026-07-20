package edu.bu.archive.adapter.in.web.dto.award;

public record AwardFundingResponse(

        String awardNumber,

        String sponsor,

        String primeSponsor,

        String sponsorAwardNumber,

        String leadUnit,

        Long linkedProposalCount,

        Long activeProposalCount

) {
}
