# 공통표준 진단기준 생성기 개발 가이드

## 1. 문서 목적

이 문서는 `stndGenerator` 애플리케이션의 인수인계, 유지보수, 배포 및 기능 확장을 위한 개발 가이드다.

현재 애플리케이션의 핵심 목표는 다음과 같다.

- 공통표준 시스템과 시스템별 WDQ 채번 영역 관리
- 유지보수 업체의 WISE DQ 결과보고서 분석
- 별도 진단기준 엑셀 파일의 유형별 등록
- 두 입력 트랙의 프로젝트 단위 병합 및 수정 이력 관리
- WDQ 9.0, 9.1, 9.2에서 실행할 SQL·패치 EXE·삭제 EXE 생성
- 2025년에 사용한 WDQ SQL의 테이블 구조와 의미 유지
- 향후 `result-review` 플랫폼과 공유할 검토 모듈의 프로토타입 제공

## 2. 기술 구성

| 영역 | 구성 |
|---|---|
| 백엔드 | Java 21, Spring Boot 4 |
| 데이터 접근 | Spring JDBC, Spring Data JPA |
| 운영 DB | PostgreSQL 16 |
| DB 마이그레이션 | Flyway |
| 엑셀 처리 | Apache POI |
| 프런트엔드 | 정적 HTML, CSS, JavaScript |
| EXE 생성 | Go 1.25 기반 SQL Runner |
| 운영 방식 | Docker Compose |
| 기본 로컬 포트 | `18080` |
| 현재 원격 서비스 | `http://192.168.0.233:18180` |

## 3. 주요 디렉터리

```text
stndGenerator/
├─ src/main/java/kr/wise/csr/
│  ├─ api/             REST API
│  ├─ importfile/      결과보고서·진단기준 파싱과 병합
│  ├─ normalization/   정규화, 충돌 처리, ID 할당
│  ├─ project/         프로젝트·스냅샷·원본 파일 관리
│  ├─ registry/        WDQ 채번 정책
│  ├─ review/          결과보고서 검토 엔진
│  ├─ export/          워크북·SQL·EXE 생성
│  ├─ validation/      WDQ DDL 및 참조 유효성 검증
│  └─ system/          공통표준 시스템 관리
├─ src/main/resources/
│  ├─ db/migration/    Flyway 마이그레이션
│  ├─ go-runner/       패치·삭제 EXE 소스
│  ├─ installer/       Windows 실행 보조 스크립트
│  └─ static/          웹 화면
├─ data/               로컬 원본 파일 저장소
├─ deploy/linux/       Docker 배포 자료
├─ docs/               개발·운영 문서
└─ target/             빌드 및 생성 산출물
```

## 4. 핵심 도메인 구조

### 4.1 공통표준 시스템

최상위 관리 단위다. 기관별 시스템이 아니라 하나의 공통표준 시스템을 한 번 등록한다.

주요 값:

- 시스템 코드
- 공통표준 시스템명
- DBMS 유형 코드
- DBMS 물리명: `WAA_DB_CONN_TRG.DB_CONN_TRG_PNM`과 SQL의 DBMS명에 사용
- 기본 스키마
- 시스템 채번 영역
- 공통 prefix
- 사용 여부

DBMS 물리명은 결과보고서의 샘플 DB명이 아니라 실제 배포 시 사용할 명칭으로 등록해야 한다.

### 4.2 프로젝트

공통표준 시스템 아래에서 연도·배포 연월별 버전으로 관리한다.

동일 시스템에 새 파일을 등록할 때 기존 프로젝트를 덮지 않고 다음 버전으로 생성할 수 있다. 화면 이동 시 프로젝트 ID를 URL 해시와 선택 상태로 유지한다.

### 4.3 입력 트랙

두 입력 방식은 서로 대체 관계가 아니라 병합되는 투 트랙 구조다.

1. `RESULT_REPORT`
   - 유지보수 업체의 WISE DQ 결과보고서 한 개
   - 실제 사용한 추가 검증룰, 업무규칙, 테이블·컬럼 제외 정보를 추출
   - 결과보고서 유효성 검토 실행

2. `CRITERIA_FILES`
   - 별도로 전달받은 여러 진단기준 엑셀
   - 유형별 독립 업로드
   - 결과보고서에 없는 상세 기준을 보완

병합 시 입력 출처와 원본 파일을 유지하며, 같은 논리키의 값이 다르면 충돌로 관리한다.

## 5. 진단기준 데이터 유형

| 내부 유형 | 의미 | WDQ 대상 |
|---|---|---|
| `SYSTEM` | 시스템 문맥 | `WAA_DB_CONN_TRG` |
| `EXCLUSION` | 테이블·컬럼 제외 | `WAA_STND_EXP_OBJ` |
| `EXCLUSION_PATTERN` | 테이블명 제외기준 룰 | `WAA_STND_EXP_OBJ`, `EXP_TYP='EXR'` |
| `VERIFICATION_RULE` | 추가·기본 검증룰 | `WAA_VRFC_RULE` 또는 기존 ID 참조 |
| `CODE_RULE` | 공통·목록성 코드규칙 | `WAA_CD_RULE` |
| `CODE_VALUE` | 목록성코드 데이터 | `WAA_CD_LIST` |
| `COLUMN_MAPPING` | 컬럼과 검증·코드 규칙 연결 | `WAA_STND_RULE_SET` |
| `BUSINESS_RULE` | 업무규칙 | `WAA_STND_TBL_PRF` |

## 6. 파싱과 병합 흐름

```text
원본 파일 저장
  → 파일 유형 감지
  → 유형별 WorkbookParser
  → ImportCandidate 생성
  → 공통 prefix 적용
  → 논리키 기반 병합
  → 충돌 생성 또는 자동 병합
  → WDQ ID 할당
  → normalized_item 스냅샷 저장
  → 검토·검증
  → SQL/EXE 생성
```

관련 핵심 클래스:

- `WorkbookDetector`
- `WisedqResultWorkbookParser`
- `SplitCriteriaWorkbookParser`
- `QualityCriteriaWorkbookParser`
- `ProjectImportService`
- `NormalizationService`
- `ProjectNormalizationService`
- `ProjectSnapshotRepository`

## 7. WDQ ID 정책

시스템별 채번 영역을 사용한다. 채번 정책은 `system_id_policy`와 `id_registry`에서 관리한다.

기본 ID 유형:

| 유형 | 기본 접두어 |
|---|---|
| DB 연결 | `STNDDB_` |
| 제외 기준 | `STNDEXP_` |
| 추가 검증룰 | `STNDRULE_` |
| 코드규칙 | `STNDCD_` |
| 컬럼 매핑 | `STND_` |
| 업무규칙 | `STNDPRF_` |

기본 검증룰 ID인 `STAT_...`, `VRF1_...`는 시스템 채번 대상이 아니다. WDQ에 이미 존재하는 ID를 그대로 참조한다.

## 8. 2025 SQL 호환 규칙

SQL 생성 로직 수정 시 가장 먼저 지켜야 하는 기준이다.

### 8.1 기본 검증룰

- `STAT_`, `VRF1_` ID는 `WAA_VRFC_RULE`에 INSERT하지 않는다.
- `WAA_STND_RULE_SET.VRFC_ID`에서 기존 ID만 참조한다.
- 일반 검증 매핑의 `RULE_SET_TYP`은 `VRFC`다.

### 8.2 추가 검증룰

- 기본 카탈로그에 없는 규칙만 `STNDRULE_...`로 생성한다.
- 공통 prefix를 검증룰명에 적용한다.
- 품질지표명은 `WAM_DQI.DQI_LNM`으로 `DQI_ID`를 조회해 연결한다.

### 8.3 코드규칙

코드 컬럼 매핑은 다음 세 값을 모두 가져야 한다.

```text
RULE_SET_TYP = 'CD'
VRFC_ID      = 'STNDCD_...'
CD_CLS_ID    = 실제 코드분류 ID
```

예:

```sql
'CD', 'STNDCD_80000002', 'LRG009'
```

- 공통코드 `CC`: 대상 DB에 `CD_SQL`을 실행한다.
- 목록성코드 `LC`: `WAA_CD_LIST` 데이터가 있을 때만 `EXL_YN='Y'`를 사용한다.
- 코드규칙 ID와 코드분류 ID를 같은 필드로 취급하면 안 된다.

### 8.4 제외기준 룰

테이블명 패턴 룰은 개별 테이블 제외로 미리 펼치지 않고 다음 형식으로 유지한다.

```text
EXP_TYP              = 'EXR'
TBL_EXP_STND_RULE    = TMP, BAK, YYYYMMDD 등
INCLD_REL            = F 또는 B
TBL_EXP_STND_RULE_RSN = 제외 사유
```

엑셀 표시값 변환:

| 엑셀 값 | 저장값 |
|---|---|
| 앞 | `F` |
| 뒤 | `B` |
| 뒤 + `_TMP` | `B` + `TMP` |

WDQ 스케줄 서버가 구분용 `_`를 자동으로 붙이므로 SQL에는 중복해서 넣지 않는다.

### 8.5 스키마와 DBMS명

- 결과보고서 첫 화면의 샘플 스키마보다 실제 상세 시트에서 사용한 스키마를 우선한다.
- DBMS명은 등록 시스템의 DBMS 물리명을 사용한다.
- 유지보수 업체의 샘플 DB명과 실제 배포 DB명이 다를 수 있음을 전제로 한다.

### 8.6 제외 대상

- 진단규칙 또는 업무규칙이 연결된 컬럼과 그 테이블은 진단 대상이므로 `EXP_YN='N'`으로 생성한다.
- 테이블 `EXP_YN='N'`과 컬럼 제외 데이터를 혼동하지 않는다.
- 과거 제외 데이터 삭제 시 `WAA_DB_SCH.DB_SCH_ID` 조인을 통해 `WAA_EXP_TBL`, `WAA_EXP_COL`까지 정리한다.

## 9. 검증 체계

### 9.1 결과보고서 검토

검토 엔진은 결과보고서를 공통표준 진단기준으로 채택해도 되는지 판단한다.

대표 판정:

- 적합
- 조건부 적합
- 부적합

대표 확인 항목:

- 추가 검증룰의 식과 품질지표
- 업무규칙 SQL
- 테이블·컬럼 제외 정보
- 코드 도메인 컬럼과 코드규칙 연결 여부
- 시스템·스키마 문맥
- WDQ DDL 길이 제한

검토 규칙 세부 내용은 `docs/dqexam-review-rules.md`를 참고한다.

### 9.2 WDQ DDL 검증

`ProjectValidator`는 SQL 생성 전에 다음을 확인한다.

- 필수 물리명 누락
- 존재하지 않는 규칙 참조
- 검증룰·업무규칙의 품질지표 누락
- WDQ 컬럼 최대 길이
- 코드규칙 SQL 누락
- 기본룰과 추가룰 ID 처리

오류가 있으면 SQL·EXE 생성을 차단한다.

## 10. 주요 API

### 시스템

```text
GET    /api/systems
POST   /api/systems
PATCH  /api/systems/{systemId}
DELETE /api/systems/{systemId}
GET    /api/systems/{systemId}/id-policies
PUT    /api/systems/{systemId}/id-policies/{type}
POST   /api/systems/{systemId}/projects
```

### 프로젝트와 입력

```text
GET  /api/projects/{projectId}
POST /api/projects/{projectId}/imports/result-report
POST /api/projects/{projectId}/imports/criteria
POST /api/projects/{projectId}/reextract
POST /api/projects/{projectId}/reextract/result-report
POST /api/projects/{projectId}/reextract/criteria
POST /api/projects/{projectId}/validation
```

### 산출물

```text
GET /api/projects/{projectId}/artifacts/workbook
GET /api/projects/{projectId}/artifacts/sql
GET /api/projects/{projectId}/artifacts/exe
GET /api/projects/{projectId}/artifacts/delete-exe
GET /api/projects/{projectId}/artifacts/history
```

### 진단기준 관리

```text
GET    /api/projects/{projectId}/criteria
POST   /api/projects/{projectId}/criteria
DELETE /api/projects/{projectId}/criteria/{itemId}
GET    /api/projects/{projectId}/criteria/history
POST   /api/projects/{projectId}/criteria/code-values/import
GET    /api/criteria-templates/{category}
```

## 11. 로컬 개발

### 11.1 필수 환경

- JDK 21 이상
- PostgreSQL 16 권장
- Windows PowerShell
- Maven Wrapper 사용 가능
- EXE 생성 시 Go 도구 필요

### 11.2 환경변수

| 환경변수 | 기본값 또는 의미 |
|---|---|
| `CSR_DB_URL` | `jdbc:postgresql://localhost:5432/csr` |
| `CSR_DB_USERNAME` | PostgreSQL 사용자 |
| `CSR_DB_PASSWORD` | PostgreSQL 비밀번호 |
| `CSR_SERVER_PORT` | 기본 `18080` |
| `CSR_STORAGE_ROOT` | 기본 `./data` |
| `CSR_INSTALLER_WORK_ROOT` | 기본 `./target/installer-builds` |
| `CSR_GO_COMMAND` | Go 실행 파일 |
| `CSR_GO_ROOT` | Go 설치 루트 |
| `CSR_RUNNER_DB_HOST` | EXE가 접근할 WDQ DB 호스트 |
| `CSR_RUNNER_DB_PORT` | 기본 `43396` |
| `CSR_RUNNER_DB_NAME` | 기본 `dqlite` |
| `CSR_RUNNER_DB_USER` | WDQ DB 사용자 |
| `CSR_RUNNER_DB_PASSWORD` | WDQ DB 비밀번호 |
| `CSR_RUNNER_VERSION_FILE` | WDQ 버전 파일 |

비밀번호는 소스·문서·Git에 저장하지 않고 환경변수 또는 배포 서버의 `.env`에서 관리한다.

### 11.3 빌드와 실행

```powershell
.\mvnw.cmd clean package
java -jar target\common-standard-rule-builder-0.0.1-SNAPSHOT.jar
```

개발 실행:

```powershell
.\mvnw.cmd spring-boot:run
```

상태 확인:

```powershell
Invoke-WebRequest http://localhost:18080/actuator/health
```

## 12. 테스트 원칙

현재 프로젝트는 빠른 기능 개발을 위해 최소 테스트 전략을 사용한다.

변경 영향에 맞춰 관련 테스트만 먼저 실행한다.

```powershell
.\mvnw.cmd "-Dtest=DatasetSqlExporterTest,SplitCriteriaWorkbookParserTest" test
```

배포 전 최소 확인:

1. 변경 영역 단위 테스트
2. `mvn package`
3. 프로젝트 재검증
4. SQL ZIP 생성
5. 2025 SQL과 테이블별 구조 비교
6. 패치 EXE·삭제 EXE 생성
7. 서버 `/actuator/health`

## 13. 원격 배포

현재 배포 디렉터리:

```text
/home/wiseitech/stnd-generator
```

서비스는 Docker Compose의 `app`, `postgres` 컨테이너로 구성된다.

일반 배포 순서:

```powershell
.\mvnw.cmd -DskipTests package
scp -P 2222 target\common-standard-rule-builder-0.0.1-SNAPSHOT.jar `
  wiseitech@192.168.0.233:/home/wiseitech/stnd-generator/app/common-standard-rule-builder.jar
ssh -p 2222 wiseitech@192.168.0.233
```

원격 서버:

```bash
cd ~/stnd-generator
docker compose up -d --build app
docker compose ps
docker compose logs --tail=200 app
```

상태 확인:

```text
http://192.168.0.233:18180/actuator/health
```

Flyway 마이그레이션은 애플리케이션 시작 시 자동 적용된다. 운영 DB 백업 없이 마이그레이션 파일을 수정하거나 기존 버전 번호를 재사용하지 않는다.

## 14. 장애 분석

### SQL 실행 오류

1. EXE 실행 로그 경로 확인
2. 오류 테이블과 키 확인
3. 생성 ZIP의 해당 SQL 파일 확인
4. WDQ DDL 길이와 PK 구성 확인
5. 과거 데이터 삭제 범위 확인

### 공통코드 조회가 0건

공통코드 `CC`는 대상 DB에서 SQL을 실행한다. 다음을 확인한다.

- 코드 분류 테이블 데이터 존재
- 상세 코드 테이블 데이터 존재
- 분류 ID 조인 결과
- `CD_ID IS NOT NULL`
- 대상 계정의 실제 스키마 또는 synonym

목록성코드 `LC`는 로컬 목록 데이터를 사용하므로 CC와 동작 방식이 다르다.

### EXE 빌드 Access denied

- 동일 출력 파일을 실행 중인지 확인
- 임시 빌드 파일 잠금 확인
- 빌드별 고유 임시 디렉터리 사용
- 백신 또는 Windows Defender 차단 로그 확인

### 재추출 실패

```bash
cd ~/stnd-generator
docker compose logs --tail=200 app
```

원본 파일은 프로젝트 저장소에 유지되므로 실패 원인을 수정한 후 같은 트랙을 다시 재추출할 수 있다.

## 15. 변경 시 필수 점검표

- [ ] 기본룰 `STAT_`, `VRF1_`를 INSERT하지 않는가
- [ ] 검증 매핑이 `VRFC`인가
- [ ] 코드 매핑이 `CD + STNDCD + CD_CLS_ID`인가
- [ ] 제외기준 룰이 `EXR + F/B`로 생성되는가
- [ ] 코드 `CC/LC`와 `EXL_YN` 처리가 맞는가
- [ ] 상세 시트의 실제 스키마를 사용하는가
- [ ] DBMS 물리명이 등록 시스템 값과 일치하는가
- [ ] 공통 prefix가 중복 적용되지 않는가
- [ ] 품질지표가 `WAM_DQI`에 연결되는가
- [ ] 기존 프로젝트 원본 파일과 수정값이 유지되는가
- [ ] 재추출 후 충돌과 검증 오류가 없는가
- [ ] 패치·삭제 SQL의 과거 데이터 정리 범위가 같은가
- [ ] WDQ 9.0, 9.1, 9.2 SQL-only 실행 구조를 유지하는가

