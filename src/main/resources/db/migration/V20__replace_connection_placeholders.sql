update standard_system
set connection_url = case dbms_type
    when 'ORA' then 'jdbc:oracle:thin:@아이피:포트:SID'
    when 'MRA' then 'jdbc:mariadb://아이피:포트/DB명'
    when 'MYS' then 'jdbc:mysql://아이피:포트/DB명'
    when 'POS' then 'jdbc:postgresql://아이피:포트/DB명'
    when 'MSQ' then 'jdbc:sqlserver://아이피:포트;databaseName=DB명'
    when 'TIB' then 'jdbc:tibero:thin:@아이피:포트:SID'
    else 'jdbc:DBMS://아이피:포트/DB명'
end
where connection_url is null or btrim(connection_url) = '' or connection_url in ('입력해주세요', '입력해주세요.');

update standard_system
set driver_name = case dbms_type
    when 'ORA' then 'oracle.jdbc.driver.OracleDriver'
    when 'MRA' then 'org.mariadb.jdbc.Driver'
    when 'MYS' then 'com.mysql.cj.jdbc.Driver'
    when 'POS' then 'org.postgresql.Driver'
    when 'MSQ' then 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
    when 'TIB' then 'com.tmax.tibero.jdbc.TbDriver'
    else 'JDBC 드라이버 클래스명'
end
where driver_name is null or btrim(driver_name) = '' or driver_name in ('입력해주세요', '입력해주세요.');
