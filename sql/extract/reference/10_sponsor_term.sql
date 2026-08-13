SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 293 rows as of live verification against
-- BU's real Oracle instance. SPONSOR_TERM_ID (this table's own
-- surrogate PK) is what archive.award_sponsor_term.sponsor_term_id
-- points at - SPONSOR_TERM_CODE is the separate, human-readable value
-- Kuali's UI displays (live-verified: SPONSOR_TERM_ID 370 has
-- SPONSOR_TERM_CODE 64, different values in overlapping ranges).

SELECT
    st.SPONSOR_TERM_ID,
    st.SPONSOR_TERM_CODE,
    st.SPONSOR_TERM_TYPE_CODE,
    st.DESCRIPTION,

    st.UPDATE_TIMESTAMP,
    st.UPDATE_USER,
    st.VER_NBR

FROM SPONSOR_TERM st

ORDER BY st.SPONSOR_TERM_ID;
