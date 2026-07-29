alter table normalized_item drop constraint if exists normalized_item_data_type_check;
alter table normalized_item add constraint normalized_item_data_type_check
    check (data_type in (
        'SYSTEM','EXCLUSION','EXCLUSION_PATTERN','VERIFICATION_RULE','CODE_RULE','CODE_VALUE',
        'COLUMN_MAPPING','BUSINESS_RULE'
    ));
