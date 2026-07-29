package kr.wise.csr.system;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StandardSystemService {
    private final JdbcTemplate jdbc;

    public StandardSystemService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SystemView> list() {
        return jdbc.query("""
                select s.id,s.system_code,s.system_name,s.dbms_type,s.dbms_physical_name,
                  s.default_schema_original,s.wdq_namespace,s.active,
                  (select count(*) from build_project p where p.system_id=s.id),s.updated_at
                  ,s.connection_logical_name,s.dbms_version_code,s.connection_url,s.driver_name,
                  s.db_account_id,s.db_account_password,s.info_system_code,s.info_system_name,s.organization_name,
                  s.criteria_prefix
                from standard_system s order by s.system_name,s.id
                """, (rs,n) -> new SystemView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getBoolean(8),rs.getInt(9),
                        rs.getObject(10,OffsetDateTime.class),rs.getString(11),rs.getString(12),rs.getString(13),
                        rs.getString(14),rs.getString(15),rs.getString(16),rs.getString(17),rs.getString(18),rs.getString(19),
                        rs.getString(20)));
    }

    @Transactional
    public SystemView create(UpdateSystem command) {
        String namespace=requireNamespace(command.wdqNamespace());
        String canonical=canonicalName(command.systemName());
        boolean duplicate=list().stream().anyMatch(system -> canonicalName(system.systemName()).equals(canonical));
        if(duplicate) throw new IllegalArgumentException("이미 등록된 공통표준 시스템입니다: "+command.systemName());
        long id=jdbc.queryForObject("""
                insert into standard_system(system_code,system_name,dbms_type,dbms_physical_name,
                  default_schema_original,default_schema_normalized,wdq_namespace,active,connection_logical_name,
                  dbms_version_code,connection_url,driver_name,db_account_id,db_account_password,
                  info_system_code,info_system_name,organization_name,criteria_prefix)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
                """,Long.class,command.systemCode().trim(),command.systemName().trim(),DbmsTypeCodes.normalize(command.dbmsType()),
                command.dbmsPhysicalName().trim(),command.defaultSchema().trim(),command.defaultSchema().trim(),
                namespace,command.active(),value(command.connectionLogicalName(),command.dbmsPhysicalName()),
                nullable(command.dbmsVersionCode()),connectionUrl(command.connectionUrl(),command.dbmsType()),
                driverName(command.driverName(),command.dbmsType()),requiredValue(command.dbAccountId()),
                requiredValue(command.dbAccountPassword()),nullable(command.infoSystemCode()),
                requiredValue(command.infoSystemName()),requiredValue(command.organizationName()),
                criteriaPrefix(command.criteriaPrefix()));
        createDefaultPolicies(id);
        return list().stream().filter(v->v.id()==id).findFirst().orElseThrow();
    }

    private void createDefaultPolicies(long systemId) {
        jdbc.update("""
                insert into system_id_policy(system_id,id_type,id_prefix,number_width)
                values (?, 'DB_CONNECTION', 'STNDDB_', 8), (?, 'EXCLUSION', 'STNDEXP_', 7),
                  (?, 'VERIFICATION_RULE', 'STNDRULE_', 7), (?, 'CODE_RULE', 'STNDCD_', 8),
                  (?, 'COLUMN_MAPPING', 'STND_', 10), (?, 'BUSINESS_RULE', 'STNDPRF_', 7)
                """,systemId,systemId,systemId,systemId,systemId,systemId);
    }

    public List<IdPolicyView> policies(long systemId) {
        return jdbc.query("""
                select id,id_type,id_prefix,number_width,last_value,active,updated_at
                from system_id_policy where system_id=? order by id_type
                """, (rs,n) -> new IdPolicyView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),
                        rs.getLong(5),rs.getBoolean(6),rs.getObject(7,OffsetDateTime.class)), systemId);
    }

    @Transactional
    public SystemView update(long id, UpdateSystem command) {
        String namespace=requireNamespace(command.wdqNamespace());
        SystemView current=list().stream().filter(system->system.id()==id).findFirst()
                .orElseThrow(()->new IllegalArgumentException("시스템을 찾을 수 없습니다: "+id));
        String currentNamespace=jdbc.query("select wdq_namespace from standard_system where id=?",
                rs -> rs.next() ? rs.getString(1) : null,id);
        if(currentNamespace==null) throw new IllegalArgumentException("시스템을 찾을 수 없습니다: "+id);
        if(!currentNamespace.equals(namespace)) {
            Integer issued=jdbc.queryForObject("select count(*) from id_registry where system_id=?",Integer.class,id);
            if(issued!=null&&issued>0)
                throw new IllegalArgumentException("이미 ID가 발급되어 시스템 채번 영역을 변경할 수 없습니다");
        }
        int changed = jdbc.update("""
                update standard_system set system_code=?,system_name=?,dbms_type=?,dbms_physical_name=?,
                  default_schema_original=?,default_schema_normalized=?,wdq_namespace=?,active=?,updated_at=now()
                  ,connection_logical_name=?,dbms_version_code=?,connection_url=?,driver_name=?,
                  db_account_id=?,db_account_password=?,info_system_code=?,info_system_name=?,organization_name=?,
                  criteria_prefix=?
                where id=?
                """, command.systemCode().trim(),command.systemName().trim(),DbmsTypeCodes.normalize(command.dbmsType()),
                command.dbmsPhysicalName().trim(),command.defaultSchema().trim(),command.defaultSchema().trim(),
                namespace,command.active(),preserve(command.connectionLogicalName(),current.connectionLogicalName()),
                preserve(command.dbmsVersionCode(),current.dbmsVersionCode()),preserve(command.connectionUrl(),current.connectionUrl()),
                preserve(command.driverName(),current.driverName()),preserve(command.dbAccountId(),current.dbAccountId()),
                preserve(command.dbAccountPassword(),current.dbAccountPassword()),preserve(command.infoSystemCode(),current.infoSystemCode()),
                preserve(command.infoSystemName(),current.infoSystemName()),preserve(command.organizationName(),current.organizationName()),
                criteriaPrefix(command.criteriaPrefix()),id);
        if (changed == 0) throw new IllegalArgumentException("시스템을 찾을 수 없습니다: " + id);
        jdbc.update("""
                update build_project set status=case when status in ('APPROVED','GENERATED','VALIDATED')
                  then 'NEEDS_REVIEW' else status end,approved_by=null,approved_at=null,
                  approved_snapshot_hash=null,updated_at=now() where system_id=?
                """,id);
        return list().stream().filter(v -> v.id()==id).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(long id) {
        SystemView system=list().stream().filter(row->row.id()==id).findFirst()
                .orElseThrow(()->new IllegalArgumentException("시스템을 찾을 수 없습니다: "+id));
        Integer projects=jdbc.queryForObject("select count(*) from build_project where system_id=?",
                Integer.class,id);
        if(projects!=null&&projects>0)
            throw new IllegalStateException("등록된 프로젝트가 있어 시스템을 삭제할 수 없습니다. 프로젝트를 먼저 삭제하세요.");
        jdbc.update("delete from id_registry where system_id=?",id);
        jdbc.update("delete from system_id_policy where system_id=?",id);
        int deleted=jdbc.update("delete from standard_system where id=?",id);
        if(deleted!=1)throw new IllegalArgumentException("시스템을 찾을 수 없습니다: "+system.systemName());
    }

    @Transactional
    public IdPolicyView updatePolicy(long systemId, String type, UpdatePolicy command) {
        jdbc.update("""
                insert into system_id_policy(system_id,id_type,id_prefix,number_width,last_value,active)
                values(?,?,?,?,?,?) on conflict(system_id,id_type) do update set
                id_prefix=excluded.id_prefix,number_width=excluded.number_width,
                last_value=excluded.last_value,active=excluded.active,updated_at=now()
                """,systemId,type,command.idPrefix().trim(),command.numberWidth(),command.lastValue(),command.active());
        return policies(systemId).stream().filter(v -> v.idType().equals(type)).findFirst().orElseThrow();
    }

    private String requireNamespace(String value) {
        String namespace=value==null?"":value.trim();
        if(!namespace.matches("[0-9]{1,4}"))
            throw new IllegalArgumentException("시스템 채번 영역은 1~4자리 숫자여야 합니다");
        return namespace;
    }
    private String nullable(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private String value(String value,String fallback) { return value==null||value.isBlank()?fallback.trim():value.trim(); }
    private String requiredValue(String value) { return value(value,"입력해주세요."); }
    private String connectionUrl(String value,String dbmsType) {
        if(!placeholder(value)) return value.trim();
        return switch(DbmsTypeCodes.normalize(dbmsType)) {
            case "ORA" -> "jdbc:oracle:thin:@아이피:포트:SID";
            case "MRA" -> "jdbc:mariadb://아이피:포트/DB명";
            case "MYS" -> "jdbc:mysql://아이피:포트/DB명";
            case "POS" -> "jdbc:postgresql://아이피:포트/DB명";
            case "MSQ" -> "jdbc:sqlserver://아이피:포트;databaseName=DB명";
            case "TIB" -> "jdbc:tibero:thin:@아이피:포트:SID";
            default -> "jdbc:DBMS://아이피:포트/DB명";
        };
    }
    private String driverName(String value,String dbmsType) {
        if(!placeholder(value)) return value.trim();
        return switch(DbmsTypeCodes.normalize(dbmsType)) {
            case "ORA" -> "oracle.jdbc.driver.OracleDriver";
            case "MRA" -> "org.mariadb.jdbc.Driver";
            case "MYS" -> "com.mysql.cj.jdbc.Driver";
            case "POS" -> "org.postgresql.Driver";
            case "MSQ" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "TIB" -> "com.tmax.tibero.jdbc.TbDriver";
            default -> "JDBC 드라이버 클래스명";
        };
    }
    private boolean placeholder(String value) {
        return value==null||value.isBlank()||value.trim().equals("입력해주세요")||value.trim().equals("입력해주세요.");
    }
    private String preserve(String value,String current) {
        return value==null||value.isBlank()?current:value.trim();
    }
    private String canonicalName(String value){return value==null?"":value.trim().toUpperCase().replaceAll("\\s+","").replace("시스템","");}
    private String criteriaPrefix(String value) {
        String prefix=value==null||value.isBlank()?"[{year}표준시스템DB {systemName}]":value.trim();
        if(prefix.length()>300) throw new IllegalArgumentException("공통 prefix는 300자 이하여야 합니다");
        return prefix;
    }
    public record SystemView(long id,String systemCode,String systemName,String dbmsType,String dbmsPhysicalName,
            String defaultSchema,String wdqNamespace,boolean active,int projectCount,OffsetDateTime updatedAt,
            String connectionLogicalName,String dbmsVersionCode,String connectionUrl,String driverName,
            String dbAccountId,String dbAccountPassword,String infoSystemCode,String infoSystemName,
            String organizationName,String criteriaPrefix) {}
    public record IdPolicyView(long id,String idType,String idPrefix,int numberWidth,long lastValue,boolean active,
            OffsetDateTime updatedAt) {}
    public record UpdateSystem(String systemCode,String systemName,String dbmsType,String dbmsPhysicalName,
            String defaultSchema,String wdqNamespace,boolean active,String connectionLogicalName,
            String dbmsVersionCode,String connectionUrl,String driverName,String dbAccountId,
            String dbAccountPassword,String infoSystemCode,String infoSystemName,String organizationName,
            String criteriaPrefix) {}
    public record UpdatePolicy(String idPrefix,int numberWidth,long lastValue,boolean active) {}
}
