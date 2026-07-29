# 결과보고서 유효성 검토 규칙 초안

이 문서는 233 서버의 `dqexam`과 `sample`의 WISE DQ V9.0 결과보고서 3개를 기준으로 한
공통표준 진단규칙 채택 전 검토 규칙이다.

## 판정

- `PASS`: 차단 오류와 확인 경고가 없다.
- `CONDITIONAL`: 차단 오류는 없지만 담당자 확인이 필요한 경고가 있다.
- `FAIL`: 공통표준 진단규칙을 생성하면 안 되는 차단 오류가 있다.

## 1차 구현 규칙

| 코드 | 대상 | 조건 | 심각도 | 채택 차단 |
|---|---|---|---|---|
| `FILE_READ_ERROR` | 파일 | 엑셀을 읽을 수 없음 | ERROR | Y |
| `MISSING_REQUIRED_SHEET` | 파일 | 값진단결과, 진단대상테이블, 도메인, 진단항목실행정보 중 누락 | ERROR | Y |
| `MISSING_OPTIONAL_SHEET` | 파일 | 참조무결성, 업무규칙, 진단항목오류정보 중 누락 | WARNING | N |
| `MISSING_REPORT_METADATA` | 값진단결과 | 기관명, 정보시스템명, DBMS명, 스키마명, DBMS종류 누락 | ERROR | Y |
| `MISSING_REQUIRED_HEADER` | 각 시트 | 검토에 필요한 헤더 누락 | ERROR | Y |
| `UNCOLLECTED_TABLE` | 진단대상테이블 | 상태가 미수집인 테이블 존재 | ERROR | Y |
| `EXCLUSION_REASON_MISSING` | 진단대상테이블 | 제외 테이블에 의견/사유가 없음 | WARNING | N |
| `TABLE_SUMMARY_MISMATCH` | 값진단결과/진단대상테이블 | 요약 건수와 상세 건수가 다름 | ERROR | Y |
| `RULE_EXPRESSION_MISSING` | 도메인 | 검증룰명은 있으나 검증룰이 없음 | ERROR | Y |
| `RULE_NOT_EXECUTED` | 도메인/진단항목실행정보 | 추가 검증룰의 실행 내역을 찾을 수 없음 | ERROR | Y |
| `DIAGNOSTIC_RULE_COVERAGE_MISSING` | 도메인/진단항목실행정보 | 진단대상 컬럼에 진단규칙과 제외사유가 모두 없음 | WARNING | N |
| `EXECUTION_NOT_COMPLETED` | 진단항목실행정보 | 실행상태가 COMPLETED가 아님 | ERROR | Y |
| `BUSINESS_RULE_SQL_MISSING` | 업무규칙 | 업무규칙명은 있으나 대상/오류 SQL이 없음 | ERROR | Y |

## dqexam 호환 범위

현재 단계에서는 dqexam의 파일 형식 판별, 진단대상/도메인/업무규칙/실행정보 검토와
최종 인정 여부를 구조화된 JSON 결과로 재현한다. 기존 `검토결과` 엑셀 서식 생성과
작업용 MariaDB 적재는 후속 단계에서 호환성 비교 후 추가한다.
