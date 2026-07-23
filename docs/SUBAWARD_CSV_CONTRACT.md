# Subaward CSV Contract

## Scope and authority

These CSV contracts correspond exactly to the SELECT lists in `oracle/subaward/`. Physical table and column names come from `reference/kuali/subaward-oracle-columns.txt`; relationships come from `reference/kuali/subaward-ojb.xml`.

The supplied Oracle inventory contains table and column names only. It does not contain the `NULLABLE` attribute from `ALL_TAB_COLUMNS`, constraint metadata, or data types. Consequently, primary and parent keys below are descriptor-defined, while nullability must still be measured in Oracle. Export and loader design must permit nulls in every non-primary-key field until Oracle constraint metadata and data profiling prove otherwise. A left-joined lookup description is always nullable when its source code is null or unmatched.

## CSV contracts

### `subawards.csv`

- Source: `KCOEUS.SUBAWARD`, enriched from `KCOEUS.SUBAWARD_STATUS`, `KCOEUS.SUBAWARD_EXTENSION`, and `KCOEUS.SUBAWARD_DOCUMENT`.
- Header: `subaward_id,document_number,sequence_number,subaward_code,organization_id,start_date,end_date,subaward_type_code,purchase_order_num,title,status_code,status_description,account_number,vendor_number,requisitioner_id,requisitioner_unit,archive_location,closeout_date,comments,site_investigator,cost_type,date_of_fully_executed,requisition_number,fed_award_proj_desc,f_and_a_rate,de_minimus,subaward_sequence_status,ffata_required,fsrs_subaward_number,award_prime_sponsor_name,award_sponsor_name,extension_date_received,update_timestamp,update_user,ver_nbr,obj_id,document_update_timestamp,document_update_user,document_ver_nbr,document_obj_id`
- Primary key: `subaward_id`.
- Parent key: `document_number` → `SUBAWARD_DOCUMENT.DOCUMENT_NUMBER`.
- Sequence fields: `subaward_code`, `sequence_number`, `subaward_sequence_status`; `ver_nbr` is the source row-lock version.
- Lookup joins: `status_code` → `SUBAWARD_STATUS.SUBAWARD_STATUS_CODE`. The document and BU extension joins preserve parent/extension attributes rather than lookup descriptions.
- Nullable columns: not physically specified by the supplied inventory. `status_description`, extension fields, and document audit fields are nullable by construction because their joins are `LEFT JOIN`.
- Unresolved external joins: organization, Award type, requisitioner unit, site-investigator Rolodex, and cost type.
- Load order: 1 (parent/version dataset).

### `subaward_amounts.csv`

- Source: `KCOEUS.SUBAWARD_AMOUNT_INFO`.
- Header: `subaward_amount_info_id,subaward_id,subaward_code,sequence_number,obligated_amount,obligated_change,obligated_change_direct,obligated_change_indirect,anticipated_amount,anticipated_change,anticipated_change_direct,anticipated_change_indirect,rate,effective_date,modification_effective_date,modification_number,modification_type_code,modification_type_description,performance_start_date,performance_end_date,purchase_order_num,comments,file_data_id,file_name,mime_type,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_amount_info_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `ver_nbr`.
- Lookup joins: `modification_type_code` → `SUBAWARD_MODIFICATION_TYPE.CODE`.
- Attachment metadata: `file_data_id`, `file_name`, and `mime_type`; no payload is selected.
- Nullable columns: not physically specified; `modification_type_description` is nullable by construction.
- Unresolved external joins: backing file-data object.
- Load order: 2.

### `subaward_contacts.csv`

- Source: `KCOEUS.SUBAWARD_CONTACT`.
- Header: `subaward_contact_id,subaward_id,subaward_code,sequence_number,contact_type_code,rolodex_id,requisitioner_id,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_contact_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `ver_nbr`.
- Lookup joins: none; the verified inventory does not contain the external contact-type or Rolodex tables.
- Attachment metadata: none.
- Nullable columns: not physically specified.
- Unresolved external joins: Award contact type and Rolodex.
- Load order: 2.

### `subaward_custom_data.csv`

- Source: `KCOEUS.SUBAWARD_CUSTOM_DATA`.
- Header: `subaward_custom_data_id,subaward_id,subaward_code,sequence_number,custom_attribute_id,value,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_custom_data_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `ver_nbr`.
- Lookup joins: none; the common custom-attribute table is outside the verified inventory.
- Attachment metadata: none.
- Nullable columns: not physically specified.
- Unresolved external joins: custom-attribute name, type, and configuration.
- Load order: 2.

### `subaward_funding.csv`

- Source: `KCOEUS.SUBAWARD_FUNDING_SOURCE`.
- Header: `subaward_funding_source_id,subaward_id,subaward_code,sequence_number,award_id,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_funding_source_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `ver_nbr`.
- Lookup joins: none.
- Attachment metadata: none.
- Nullable columns: not physically specified.
- Unresolved external joins: `award_id` → the physical Award version row and its stable Award number.
- Load order: 2, after Subaward versions; archive Award resolution may run later.

### `subaward_attachments.csv`

- Source: `KCOEUS.SUBAWARD_ATTACHMENTS`.
- Header: `attachment_id,subaward_id,subaward_code,sequence_number,attachment_type_code,attachment_type_description,document_id,file_data_id,file_name,mime_type,document_status_code,description,last_update_timestamp,last_update_user,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `attachment_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `ver_nbr`.
- Lookup joins: `attachment_type_code` → `SUBAWARD_ATTACHMENT_TYPE.ATTACHMENT_TYPE_CODE`.
- Attachment metadata: `document_id`, `file_data_id`, `file_name`, `mime_type`, `document_status_code`, description, and both audit pairs. Binary content is deliberately excluded.
- Nullable columns: not physically specified; `attachment_type_description` is nullable by construction.
- Unresolved external joins: backing document/file-data object and file lifecycle semantics.
- Load order: 2 for metadata; approved payloads would load separately.

### `subaward_closeout.csv`

- Source: `KCOEUS.SUBAWARD_CLOSEOUT`.
- Header: `subaward_closeout_id,subaward_id,subaward_code,sequence_number,closeout_number,closeout_type_code,date_requested,date_followup,date_received,comments,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_closeout_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `closeout_number`, `ver_nbr`.
- Lookup joins: none; `CLOSEOUT_TYPE` is described by OJB but absent from the verified inventory.
- Attachment metadata: none.
- Nullable columns: not physically specified; the milestone dates and comments must be treated as nullable until profiled.
- Unresolved external joins: closeout-type description.
- Load order: 2.

### `subaward_reports.csv`

- Source: `KCOEUS.SUBAWARD_REPORTS`.
- Header: `subaward_report_id,subaward_id,subaward_code,sequence_number,report_type_code,report_type_description,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_report_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`, `ver_nbr`.
- Lookup joins: `report_type_code` → `SUBAWARD_REPORT_TYPE.REPORT_TYPE_CODE`.
- Attachment metadata: none.
- Nullable columns: not physically specified; `report_type_description` is nullable by construction.
- Unresolved external joins: none for the descriptor-defined report type; Oracle data types and unmatched codes still require validation.
- Load order: 2.

### `subaward_notepad.csv`

- Source: `KCOEUS.SUBAWARD_NOTEPAD`.
- Header: `subaward_notepad_id,subaward_id,subaward_code,entry_number,note_topic,comments,restricted_view,create_timestamp,create_user,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `subaward_notepad_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `entry_number`, `ver_nbr`. The physical table has no `SEQUENCE_NUMBER`.
- Lookup joins: none.
- Attachment metadata: none.
- Nullable columns: not physically specified.
- Unresolved external joins: none; restricted-note authorization and archive scope remain unresolved.
- Load order: 2, subject to authorization for restricted content.

### `subaward_notifications.csv`

- Source: `KCOEUS.SUBAWARD_NOTIFICATION`.
- Header: `notification_id,owning_document_id_fk,document_number,subaward_code,notification_type_id,recipients,subject,message,create_timestamp,update_timestamp,update_user,ver_nbr,obj_id`
- Primary key: `notification_id`.
- Parent key: descriptor-defined `owning_document_id_fk` → `SUBAWARD.SUBAWARD_ID`; this relationship requires Oracle data validation.
- Sequence fields: `subaward_code`, `ver_nbr`. The physical table has no `SEQUENCE_NUMBER`.
- Lookup joins: none; notification type is external to the verified inventory.
- Attachment metadata: none.
- Nullable columns: not physically specified.
- Unresolved external joins: notification-type description; relationship among `owning_document_id_fk`, `document_number`, and the Subaward version.
- Load order: 2 after Subaward versions, once parent-key behavior is validated.

### `subaward_template_info.csv`

- Source: `KCOEUS.SUBAWARD_TEMPLATE_INFO`.
- Header: `subaward_id,subaward_code,sequence_number,sow_or_sub_proposal_budget,sub_proposal_date,invoice_or_payment_contact,irb_iacuc_contact,final_stmt_of_costs_contact,change_requests_contact,sub_change_requests_contact,termination_contact,sub_termination_contact,no_cost_extension_contact,perf_site_diff_from_org_addr,perf_site_same_as_sub_pi_addr,sub_registered_in_ccr,sub_exempt_from_reporting_comp,parent_duns_number,parent_congressional_district,exempt_from_rprtg_exec_comp,copyright_type,automatic_carry_forward,carry_forward_requests_sent_to,treatment_prgm_income_additive,applicable_program_regulations,applicable_program_regs_date,mpi_award,mpi_leadership_plan,r_and_d,includes_cost_sharing,fcio,invoices_emailed,invoice_address_diff,invoice_email_diff,fcio_subrec_policy_cd,animal_flag,animal_pte_send_cd,animal_pte_nr_cd,human_flag,human_subjects,human_exempt_docs,human_pte_send_cd,human_pte_nr_cd,human_data_exchange_agree_cd,human_data_exchange_terms_cd,human_includes_clinical_trials,additional_terms,treatment_of_income,data_sharing_attachment,data_sharing_cd,final_statement_due_cd,update_timestamp,update_user`
- Primary key: descriptor-defined `subaward_id`.
- Parent key: `subaward_id` → `SUBAWARD.SUBAWARD_ID`.
- Sequence fields: `subaward_code`, `sequence_number`. The physical table has no `VER_NBR`.
- Lookup joins: none; no referenced template-info lookup table is present in the verified inventory.
- Attachment metadata: `data_sharing_attachment` is preserved as stored but is not a verified file identifier.
- Nullable columns: not physically specified; template/compliance fields must be treated as nullable until profiled.
- Unresolved external joins: contact-code semantics, copyright type, policy/code descriptions, and whether `data_sharing_attachment` identifies external content.
- Load order: 2.

## Omitted physical entities and fields

- Binary columns are not selected. In particular, `SUBAWARD_AMT_RELEASED.DOCUMENT` and `SUBAWARD_FORMS.FORM` are outside this metadata-only slice.
- Backup, conversion, deleted, temporary, and PMC tables are excluded as required, including every table containing `BKUP`, `BAK`, `TEMP`, `CONVERSION`, `DELETED`, or `PMC` in its name.
- `SUBAWARD_AMT_RELEASED`, `SUBAWARD_FFATA_REPORTING`, `SUBAWARD_COMMENT`, `SUBAWARD_TEMPLATE_ATTACHMENTS`, and `SUBAWARD_FORMS` are verified physical entities but have no requested primary export in this slice. Their non-binary metadata remains a future scope decision.
- `SUBAWARD_EXTENSION` and `SUBAWARD_DOCUMENT` are folded into `subawards.csv` because the former is one-to-one with a Subaward version and the latter is its document parent.
- Lookup tables are used only for left-join enrichment and do not have standalone CSV contracts in this slice.

## SQL execution and load order

Run the exports in this order:

1. `export_subawards.sql`
2. `export_subaward_amounts.sql`
3. `export_subaward_contacts.sql`
4. `export_subaward_custom_data.sql`
5. `export_subaward_funding.sql`
6. `export_subaward_attachments.sql`
7. `export_subaward_closeout.sql`
8. `export_subaward_reports.sql`
9. `export_subaward_notepad.sql`
10. `export_subaward_notifications.sql`
11. `export_subaward_template_info.sql`

The same order is safe for a future load: the version/document dataset precedes every child. The child exports are otherwise independent in this slice.
