SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - a small, bounded lookup table. Real BU
-- row count/codes not yet verified live (see
-- database/migrations/V057's own header comment) - do not assume the
-- generic Kuali Coeus demo codes/descriptions match BU's actual Oracle
-- instance without live verification, the same caveat already proven
-- necessary for UNIT_ADMINISTRATOR_TYPE.

SELECT
    ct.COMMENT_TYPE_CODE,
    ct.DESCRIPTION,
    ct.TEMPLATE_FLAG,
    ct.CHECKLIST_FLAG,
    ct.AWARD_COMMENT_SCREEN_FLAG,

    ct.UPDATE_TIMESTAMP,
    ct.UPDATE_USER,
    ct.VER_NBR

FROM COMMENT_TYPE ct

ORDER BY ct.COMMENT_TYPE_CODE;
