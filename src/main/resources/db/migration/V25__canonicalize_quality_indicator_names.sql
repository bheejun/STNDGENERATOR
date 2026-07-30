update normalized_item
set values_json = jsonb_set(values_json, '{qualityIndicator}', to_jsonb('번호 도메인'::text), true)
where values_json->>'qualityIndicator' = '구분 도메인';

update criteria_override
set values_json = jsonb_set(values_json, '{qualityIndicator}', to_jsonb('번호 도메인'::text), true)
where values_json->>'qualityIndicator' = '구분 도메인';

delete from default_quality_indicator
where quality_indicator_name = '구분 도메인';
