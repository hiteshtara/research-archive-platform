SELECT
    pc.proposal_comments_id AS proposal_comment_id,
    pc.proposal_id,
    pc.proposal_number,
    pc.sequence_number,

    pc.comment_type_code,
    pc.comments,

    pc.update_timestamp AS source_update_timestamp,
    pc.update_user AS source_update_user

FROM proposal_comments pc

ORDER BY
    pc.proposal_number,
    pc.sequence_number,
    pc.proposal_comments_id;
