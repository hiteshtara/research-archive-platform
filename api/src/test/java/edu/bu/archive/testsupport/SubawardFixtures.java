package edu.bu.archive.testsupport;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;

public final class SubawardFixtures {

    private SubawardFixtures() {
    }

    public static SubawardRowResponse subawardRow() {
        return new SubawardRowResponse(
                101L, "DOC-101", 4, "1004", null, null, null, null,
                null, "Subaward title", null, "Active", null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, "ACTIVE", null, null, null, null, null, null, null,
                1L, "OBJECT-1", null, null, 1L, "DOCUMENT-OBJECT-1"
        );
    }
}
