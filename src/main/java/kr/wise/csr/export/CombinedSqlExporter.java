package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.wise.csr.project.ProjectSnapshot;

@Component
public class CombinedSqlExporter {
    private final DatasetSqlExporter datasetSqlExporter;

    public CombinedSqlExporter(DatasetSqlExporter datasetSqlExporter) {
        this.datasetSqlExporter = datasetSqlExporter;
    }

    public GeneratedFile export(ProjectSnapshot snapshot) {
        List<GeneratedFile> files = datasetSqlExporter.exportDatasetSql(snapshot);
        StringBuilder sql = new StringBuilder()
                .append("-- WDQ 9.0 / 9.1 / 9.2 integrated SQL\n")
                .append("-- system: ").append(snapshot.systemName())
                .append(" / project: ").append(snapshot.projectId()).append("\n")
                .append("-- generated in the same order as the SQL ZIP (01 -> 07)\n\n");
        for (GeneratedFile file : files) {
            sql.append("-- ============================================================\n")
                    .append("-- ").append(file.fileName()).append("\n")
                    .append("-- ============================================================\n")
                    .append(new String(file.content(), StandardCharsets.UTF_8))
                    .append('\n');
        }
        String systemName = safeFileName(snapshot.systemName());
        return new GeneratedFile(systemName + ".sql", "text/plain; charset=UTF-8",
                sql.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String safeFileName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.isBlank() ? "common-standard" : name;
    }
}
