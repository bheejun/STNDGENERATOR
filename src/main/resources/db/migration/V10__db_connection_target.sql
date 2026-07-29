alter table standard_system add column connection_logical_name text;
alter table standard_system add column dbms_version_code varchar(40);
alter table standard_system add column connection_url text;
alter table standard_system add column driver_name text;
alter table standard_system add column db_account_id text;
alter table standard_system add column db_account_password text;
alter table standard_system add column info_system_code text;
alter table standard_system add column info_system_name text;
alter table standard_system add column organization_name text;

update standard_system
set connection_logical_name = dbms_physical_name,
    connection_url = case dbms_type
      when 'ORA' then 'jdbc:oracle:thin:@아이피:포트:SID'
      when 'CBR' then 'jdbc:cubrid:아이피:포트:DB명:::?charset={charset}'
      when 'MRA' then 'jdbc:mariadb://아이피:포트/DB명'
      when 'MYS' then 'jdbc:mysql://아이피:포트/DB명'
      when 'POS' then 'jdbc:postgresql://아이피:포트/DB명'
      else '입력해주세요.' end,
    driver_name = case dbms_type
      when 'ORA' then 'oracle.jdbc.driver.OracleDriver'
      when 'CBR' then 'cubrid.jdbc.driver.CUBRIDDriver'
      when 'MRA' then 'org.mariadb.jdbc.Driver'
      when 'MYS' then 'com.mysql.cj.jdbc.Driver'
      when 'POS' then 'org.postgresql.Driver'
      else '입력해주세요.' end,
    db_account_id = '입력해주세요.',
    db_account_password = '입력해주세요.',
    info_system_name = system_name,
    organization_name = '입력해주세요.';
