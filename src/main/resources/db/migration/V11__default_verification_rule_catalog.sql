create table default_verification_rule (
  rule_id varchar(30) primary key,
  rule_type varchar(10) not null,
  rule_name varchar(1000) not null unique,
  rule_expression text not null,
  description text,
  active boolean not null default true,
  source varchar(100) not null default 'WDQ 9.x WAA_VRFC_RULE',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

insert into default_verification_rule(rule_id,rule_type,rule_name,rule_expression,description) values
('STAT_00000000001','YN','[기본]여부(Y,N)','Y,N',null),
('STAT_00000000008','RNG','[기본]수량-전체','컬럼 >-999999999 AND 컬럼 < 999999999',null),
('STAT_00000000030','DTM','[기본]날짜-년월일시분초','YYYYMMDDHH24MISS',null),
('STAT_00000000053','NN','[기본]필수값','NOT NULL',null),
('VRF1_00000000066','RNG','[기본]수량-마이너스 수량 불가','컬럼 >= 0','수량은 0 이상의 숫자만 입력될 수 있다'),
('VRF1_00000000075','DTM','[기본]날짜-YYYY/MM/DD HH24:MI:SS','YYYY/MM/DD HH24:MI:SS','YYYY/MM/DD HH24:MI:SS'),
('VRF1_00000000076','DTM','[기본]날짜-YYYY/MM/DD','YYYY/MM/DD','YYYY/MM/DD'),
('VRF1_00000000079','DTM','[기본]날짜-시분','HH24:MI','HH24:MI'),
('VRF1_00000000098','FRM','[기본]형식 공백/특수문자','^[0-9|a-z|A-Z|ㄱ-ㅎ|ㅏ-ㅣ|가-힣]*$','좌우 공백, 특수문자, 한글깨짐 검증');
