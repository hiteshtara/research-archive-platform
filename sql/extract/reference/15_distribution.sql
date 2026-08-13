SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 2 rows as of live verification against
-- BU's real Oracle instance (a genuinely binary Yes/No lookup). The FK
-- target of archive.award_report_term.osp_distribution_code (Oracle's
-- own table is named DISTRIBUTION, keyed by OSP_DISTRIBUTION_CODE, not
-- a table named OSP_DISTRIBUTION - do not rename this on the way in).

SELECT
    d.OSP_DISTRIBUTION_CODE,
    d.DESCRIPTION,
    d.ACTIVE_FLAG,

    d.UPDATE_TIMESTAMP,
    d.UPDATE_USER,
    d.VER_NBR

FROM DISTRIBUTION d

ORDER BY d.OSP_DISTRIBUTION_CODE;
