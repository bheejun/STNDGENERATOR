package kr.wise.csr.export;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import kr.wise.csr.project.ProjectSnapshot;

@Component
public class WdqExeBuilder {
    private final DatasetSqlExporter sqlExporter;
    private final WdqDeleteSqlExporter deleteSqlExporter;
    private final String goCommand;
    private final Path goRoot;
    private final Path workRoot;
    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;
    private final String versionFile;

    public WdqExeBuilder(DatasetSqlExporter sqlExporter, WdqDeleteSqlExporter deleteSqlExporter,
            @Value("${csr.runner.go-command:./tools/go1.25.12/go/bin/go.exe}") String goCommand,
            @Value("${csr.runner.go-root:./tools/go1.25.12/go}") String goRoot,
            @Value("${csr.installer.work-root:./target/installer-builds}") String workRoot,
            @Value("${csr.runner.db-host:127.0.0.1}") String dbHost,
            @Value("${csr.runner.db-port:43396}") int dbPort,
            @Value("${csr.runner.db-name:dqlite}") String dbName,
            @Value("${csr.runner.db-user:root}") String dbUser,
            @Value("${csr.runner.db-password:}") String dbPassword,
            @Value("${csr.runner.version-file:C:\\WDQ\\ide\\versionInfo.txt}") String versionFile) {
        this.sqlExporter=sqlExporter; this.deleteSqlExporter=deleteSqlExporter;
        this.goCommand=resolveCommand(goCommand); this.goRoot=Path.of(goRoot).toAbsolutePath().normalize();
        this.workRoot=Path.of(workRoot).toAbsolutePath().normalize(); this.dbHost=dbHost; this.dbPort=dbPort;
        this.dbName=dbName; this.dbUser=dbUser; this.dbPassword=dbPassword; this.versionFile=versionFile;
    }

    public GeneratedFile build(ProjectSnapshot snapshot){return build(snapshot,false);}
    public GeneratedFile buildDelete(ProjectSnapshot snapshot){return build(snapshot,true);}

    private GeneratedFile build(ProjectSnapshot snapshot,boolean delete){
        Path work=null;
        try{
            verifyGo(); Files.createDirectories(workRoot);
            work=Files.createTempDirectory(workRoot,"go-project-"+snapshot.projectId()+"-");
            Path goTemp=work.resolve(".gotmp");
            Path goCache=workRoot.resolve("go-cache");
            Path goPath=workRoot.resolve("go-path");
            Path goModCache=goPath.resolve("pkg").resolve("mod");
            Files.createDirectories(goTemp);
            Files.createDirectories(goCache);
            Files.createDirectories(goModCache);
            copy("go-runner/main.go",work.resolve("main.go")); copy("go-runner/go.mod",work.resolve("go.mod"));
            copy("go-runner/go.sum",work.resolve("go.sum"));
            String sql=delete?new String(deleteSqlExporter.export(snapshot).content(),StandardCharsets.UTF_8):combine(sqlExporter.exportDatasetSql(snapshot));
            Files.writeString(work.resolve("payload.sql"),sql,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Files.writeString(work.resolve("config.json"),config(snapshot,delete),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            String fileName=outputName(snapshot,delete)+".exe"; Path executable=work.resolve(fileName);
            ProcessBuilder builder=new ProcessBuilder(goCommand,"build","-trimpath","-ldflags=-s -w","-o",fileName,".")
                    .directory(work.toFile()).redirectErrorStream(true);
            builder.environment().put("GOOS","windows"); builder.environment().put("GOARCH","amd64");
            builder.environment().put("CGO_ENABLED","0"); builder.environment().put("GOROOT",goRoot.toString());
            builder.environment().put("GOTMPDIR",goTemp.toString());
            builder.environment().put("GOCACHE",goCache.toString());
            builder.environment().put("GOPATH",goPath.toString());
            builder.environment().put("GOMODCACHE",goModCache.toString());
            builder.environment().put("GOPROXY","https://proxy.golang.org,direct");
            builder.environment().put("GOSUMDB","sum.golang.org");
            BuildResult result=runBuild(builder);
            if(result.exitCode()!=0 && result.output().toLowerCase().contains("access is denied"))
                result=runBuild(builder);
            if(result.exitCode()!=0)throw new IllegalStateException("Go EXE 빌드 실패: "+tail(result.output()));
            if(!Files.isRegularFile(executable))throw new IllegalStateException("생성된 Windows EXE를 찾을 수 없습니다");
            return new GeneratedFile(fileName,"application/vnd.microsoft.portable-executable",Files.readAllBytes(executable));
        }catch(IOException e){throw new IllegalStateException("WDQ Go EXE 생성 실패: "+e.getMessage(),e);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("WDQ Go EXE 생성이 중단되었습니다",e);}
        finally{deleteBuildDirectory(work);}
    }

    private void verifyGo(){
        if((goCommand.contains("\\")||goCommand.contains("/"))&&!Files.isRegularFile(Path.of(goCommand)))
            throw new IllegalStateException("Go 컴파일러를 찾을 수 없습니다: "+goCommand);
        if(!Files.isDirectory(goRoot))throw new IllegalStateException("GOROOT를 찾을 수 없습니다: "+goRoot);
    }
    private BuildResult runBuild(ProcessBuilder builder)throws IOException,InterruptedException {
        Process process=builder.start();
        String output;
        try(InputStream stream=process.getInputStream()){output=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        if(!process.waitFor(Duration.ofMinutes(3).toMillis(),TimeUnit.MILLISECONDS)){
            process.destroyForcibly();
            throw new IllegalStateException("Go EXE 빌드 시간이 3분을 초과했습니다");
        }
        return new BuildResult(process.exitValue(),output);
    }
    private String config(ProjectSnapshot s,boolean delete){return """
            {"system":"%s","mode":"%s","host":"%s","port":%d,"database":"%s","user":"%s","password":"%s","versionFile":"%s"}
            """.formatted(j(s.systemName()),delete?"DELETE":"PATCH",j(dbHost),dbPort,j(dbName),j(dbUser),j(dbPassword),j(versionFile));}
    private String j(String v){return v==null?"":v.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    private String combine(List<GeneratedFile> files){StringBuilder s=new StringBuilder();for(GeneratedFile f:files)s.append("-- ===== ").append(f.fileName()).append(" =====\n").append(new String(f.content(),StandardCharsets.UTF_8)).append("\n");return s.toString();}
    private String outputName(ProjectSnapshot s,boolean delete){String system=s.systemName()==null?"System":s.systemName().trim().replaceAll("[\\\\/:*?\"<>|]","_").replaceAll("\\s+","_");if(system.isBlank())system="System";return "WDQ_9.0-9.2_"+system+(delete?"_DELETE_Project_":"_SQL_Patch_Project_")+s.projectId();}
    private String resolveCommand(String value){if(value.contains("/")||value.contains("\\"))return Path.of(value).toAbsolutePath().normalize().toString();return value;}
    private void copy(String resource,Path target)throws IOException{try(InputStream in=new ClassPathResource(resource).getInputStream()){Files.copy(in,target);}}
    private String tail(String output){if(output==null)return "출력 없음";return output.length()<=3000?output.trim():output.substring(output.length()-3000).trim();}
    private void deleteBuildDirectory(Path work){if(work==null||!work.normalize().startsWith(workRoot)||!Files.exists(work))return;try(var paths=Files.walk(work)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}
    private record BuildResult(int exitCode,String output){}
}
