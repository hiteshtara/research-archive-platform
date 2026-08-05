SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 146 rows as of live verification against
-- BU's real Oracle instance, across every DOCUMENT_TYPE_CODE (not
-- filtered to any one domain, so Award/Negotiation/Subaward's own
-- custom-data features can reuse this same table later - e.g. 'AWRD'
-- for Award, 'INPR' for Institutional Proposal, 'PRDV' for Proposal
-- Development). ACTIVE_FLAG/SORT_ID/IS_REQUIRED are real per-
-- (document_type_code, custom_attribute_id) values - live-verified
-- that the SAME attribute can be active on one document type and
-- inactive on another (e.g. attribute 1120 "Activity Code" is
-- active='N' on 'INPR' specifically).

SELECT
    cad.DOCUMENT_TYPE_CODE,
    cad.CUSTOM_ATTRIBUTE_ID,
    cad.TYPE_NAME,
    cad.IS_REQUIRED,
    cad.ACTIVE_FLAG,
    cad.SORT_ID,

    cad.UPDATE_TIMESTAMP,
    cad.UPDATE_USER,
    cad.VER_NBR

FROM CUSTOM_ATTRIBUTE_DOCUMENT cad

ORDER BY cad.DOCUMENT_TYPE_CODE, cad.CUSTOM_ATTRIBUTE_ID;
