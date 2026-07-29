package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import kr.wise.csr.project.ProjectSnapshot;

class WdqExeBuilderTest {
    @Test void buildsAndChecksStandaloneWindowsExe() throws Exception {
        WdqExeBuilder builder=new WdqExeBuilder(new DatasetSqlExporter(),new WdqDeleteSqlExporter(),
                "./tools/go1.25.12/go/bin/go.exe","./tools/go1.25.12/go","./target/test-go-builds",
                "127.0.0.1",43396,"dqlite","root","test-password","C:\\WDQ\\ide\\versionInfo.txt");
        ProjectSnapshot source=DatasetSqlExporterTest.approvedSnapshot();
        ProjectSnapshot enriched=new ProjectSnapshot(source.projectId(),source.systemId(),source.targetYear(),
                source.deploymentYearMonth(),source.defaultSchema(),"테스트시스템","MAILDB","ORACLE",
                "STNDDB_00000001",source.status(),source.rows(),source.conflicts(),0,0,source.importErrors(),
                null,null,null).approved("tester",java.time.OffsetDateTime.now());
        GeneratedFile generated=builder.buildDelete(enriched);
        assertThat(generated.content()).startsWith(new byte[]{'M','Z'});
        Path exe=Path.of("target","go-runner-check.exe").toAbsolutePath();
        Files.write(exe,generated.content());
        Process process=new ProcessBuilder(exe.toString(),"--check").redirectErrorStream(true).start();
        assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();
        String output=new String(process.getInputStream().readAllBytes());
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("CHECK OK");
    }
}
