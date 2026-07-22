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
    private final Path compiler;
    private final Path workRoot;

    public WdqExeBuilder(DatasetSqlExporter sqlExporter, WdqDeleteSqlExporter deleteSqlExporter,
            @Value("${csr.installer.inno-compiler:C:/Program Files (x86)/Inno Setup 6/ISCC.exe}") String compiler,
            @Value("${csr.installer.work-root:./target/installer-builds}") String workRoot) {
        this.sqlExporter = sqlExporter;
        this.deleteSqlExporter = deleteSqlExporter;
        this.compiler = Path.of(compiler).toAbsolutePath().normalize();
        this.workRoot = Path.of(workRoot).toAbsolutePath().normalize();
    }

    public GeneratedFile build(ProjectSnapshot snapshot) { return build(snapshot, false); }
    public GeneratedFile buildDelete(ProjectSnapshot snapshot) { return build(snapshot, true); }

    private GeneratedFile build(ProjectSnapshot snapshot, boolean delete) {
        if (!Files.isRegularFile(compiler))
            throw new IllegalStateException("Inno Setup 컴파일러를 찾을 수 없습니다: " + compiler);
        Path work = null;
        try {
            Files.createDirectories(workRoot);
            work = Files.createTempDirectory(workRoot, "project-" + snapshot.projectId() + "-");
            String sqlName = delete ? "wdq_delete.sql" : "wdq_patch.sql";
            String sql = delete ? new String(deleteSqlExporter.export(snapshot).content(), StandardCharsets.UTF_8)
                    : combine(sqlExporter.exportDatasetSql(snapshot));
            Files.writeString(work.resolve(sqlName), sql, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            String batchName = delete ? "delete_sql_9x.bat" : "apply_sql_9x.bat";
            copyBatchResource("installer/" + batchName, work.resolve(batchName));
            copyResource("installer/start.txt", work.resolve("start.txt"));
            Files.writeString(work.resolve("versionInfo.txt"), versionInfo(snapshot, delete), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(work.resolve("installer.iss"), innoScript(snapshot, delete), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);

            Process process = new ProcessBuilder(compiler.toString(), "installer.iss")
                    .directory(work.toFile()).redirectErrorStream(true).start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("EXE 컴파일 시간이 2분을 초과했습니다");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("EXE 컴파일 실패: " + tail(output));
            String fileName = outputName(snapshot, delete) + ".exe";
            Path executable = work.resolve("output").resolve(fileName);
            if (!Files.isRegularFile(executable)) throw new IllegalStateException("생성된 EXE 파일을 찾을 수 없습니다");
            return new GeneratedFile(fileName, "application/vnd.microsoft.portable-executable",
                    Files.readAllBytes(executable));
        } catch (IOException e) {
            throw new IllegalStateException("WDQ SQL EXE 생성에 실패했습니다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WDQ SQL EXE 생성이 중단되었습니다", e);
        } finally { deleteBuildDirectory(work); }
    }

    private String combine(List<GeneratedFile> files) {
        StringBuilder sql = new StringBuilder();
        for (GeneratedFile file : files) sql.append("-- ===== ").append(file.fileName()).append(" =====\r\n")
                .append(new String(file.content(), StandardCharsets.UTF_8)).append("\r\n");
        return sql.toString();
    }

    private String innoScript(ProjectSnapshot snapshot, boolean delete) {
        String output = outputName(snapshot, delete);
        String sqlName = delete ? "wdq_delete.sql" : "wdq_patch.sql";
        String batchName = delete ? "delete_sql_9x.bat" : "apply_sql_9x.bat";
        String mode = delete ? "Delete" : "Patch";
        return """
                [Setup]
                AppId={{5D82DFA7-7EAB-4BA7-BE8D-9DA5F48F7B44}
                AppName=WDQ 9.x SQL %s
                AppVersion=%s-%s
                AppPublisher=WISE ITECH, Inc.
                DefaultDirName=C:\\WDQ\\patch\\project_%d
                DisableProgramGroupPage=yes
                DisableDirPage=yes
                InfoBeforeFile=start.txt
                OutputDir=output
                OutputBaseFilename=%s
                Compression=lzma
                SolidCompression=yes
                WizardStyle=modern
                PrivilegesRequired=admin
                Uninstallable=no

                [Files]
                Source: "%s"; DestDir: "{app}"; Flags: ignoreversion
                Source: "%s"; DestDir: "{app}"; Flags: ignoreversion
                Source: "versionInfo.txt"; DestDir: "{app}"; Flags: ignoreversion

                [Run]
                Filename: "{cmd}"; Parameters: "/c {app}\\%s"; WorkingDir: "{app}"; StatusMsg: "WDQ SQL processing..."; Flags: waituntilterminated

                [Code]
                function InitializeSetup(): Boolean;
                var VersionFile: String; Contents: AnsiString;
                begin
                  VersionFile := 'C:\\WDQ\\ide\\versionInfo.txt';
                  if not FileExists(VersionFile) then begin MsgBox('WDQ version file not found: ' + VersionFile, mbError, MB_OK); Result := False; Exit; end;
                  if not LoadStringFromFile(VersionFile, Contents) then begin MsgBox('WDQ version file cannot be read.', mbError, MB_OK); Result := False; Exit; end;
                  if (Pos('9.0', Contents) = 0) and (Pos('9.1', Contents) = 0) and (Pos('9.2', Contents) = 0) then begin MsgBox('This installer supports WDQ 9.0, 9.1, and 9.2 only.', mbError, MB_OK); Result := False; Exit; end;
                  Result := True;
                end;
                """.formatted(mode, snapshot.targetYear(), snapshot.deploymentYearMonth(), snapshot.projectId(),
                        output, sqlName, batchName, batchName).replace("\n", "\r\n");
    }

    private String versionInfo(ProjectSnapshot snapshot, boolean delete) {
        return "WDQ SQL " + (delete ? "Delete" : "Patch") + "\r\nTARGET: WDQ 9.0 / 9.1 / 9.2\r\nSYSTEM: "
                + snapshot.systemName() + "\r\nPROJECT: " + snapshot.projectId() + "\r\nDEPLOYMENT: "
                + snapshot.deploymentYearMonth() + "\r\nMODE: SQL ONLY\r\n";
    }

    private String outputName(ProjectSnapshot snapshot, boolean delete) {
        String system = snapshot.systemName() == null ? "System" : snapshot.systemName().trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        if (system.isBlank()) system = "System";
        return "WDQ_9.0-9.2_" + system + (delete ? "_DELETE_Project_" : "_SQL_Patch_Project_")
                + snapshot.projectId();
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) { Files.copy(input, target); }
    }
    private void copyBatchResource(String resource, Path target) throws IOException {
        String content;
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(target, content.replace("\r\n", "\n").replace("\n", "\r\n"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private String tail(String output) {
        if (output == null) return "출력 없음";
        return output.length() <= 2000 ? output.trim() : output.substring(output.length() - 2000).trim();
    }
    private void deleteBuildDirectory(Path work) {
        if (work == null || !work.normalize().startsWith(workRoot) || !Files.exists(work)) return;
        try (var paths = Files.walk(work)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
