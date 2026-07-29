package kr.wise.csr.importfile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.project.ProjectCriteriaManagementService;

@Service
public class CodeValueExcelImportService {
    private final ProjectCriteriaManagementService criteria;

    public CodeValueExcelImportService(ProjectCriteriaManagementService criteria) {
        this.criteria = criteria;
    }

    public ImportResult replace(long projectId, String ruleName, MultipartFile file) {
        if (ruleName == null || ruleName.isBlank()) throw new IllegalArgumentException("코드규칙을 선택하세요");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("코드 데이터 엑셀 파일을 선택하세요");
        List<ProjectCriteriaManagementService.CodeValueInput> values=parse(file,ruleName.trim());
        criteria.replaceCodeValues(projectId,ruleName.trim(),values);
        return new ImportResult(ruleName.trim(),values.size());
    }

    private List<ProjectCriteriaManagementService.CodeValueInput> parse(MultipartFile file,String selectedRule) {
        try(InputStream input=file.getInputStream(); Workbook workbook=WorkbookFactory.create(input)) {
            Sheet sheet=workbook.getSheet("DATA");
            if(sheet==null) sheet=workbook.getSheetAt(0);
            DataFormatter formatter=new DataFormatter(Locale.KOREA);
            Header header=findHeader(sheet,formatter);
            List<ProjectCriteriaManagementService.CodeValueInput> result=new ArrayList<>();
            Set<String> ids=new java.util.HashSet<>();
            for(int i=header.row()+1;i<=sheet.getLastRowNum();i++){
                Row row=sheet.getRow(i);
                if(row==null) continue;
                String codeId=value(row,header.codeId(),formatter);
                String codeName=value(row,header.codeName(),formatter);
                String excelRule=header.ruleName()<0?"":value(row,header.ruleName(),formatter);
                if(codeId.isBlank()&&codeName.isBlank()&&excelRule.isBlank()) continue;
                if(codeId.isBlank()) throw new IllegalArgumentException("코드값이 없습니다: "+sheet.getSheetName()+"!"+(i+1));
                if(!excelRule.isBlank()&&!excelRule.equals(selectedRule))
                    throw new IllegalArgumentException("선택한 코드규칙과 엑셀의 코드규칙명이 다릅니다: "+excelRule);
                String normalized=codeId.trim().toUpperCase(Locale.ROOT);
                if(!ids.add(normalized)) throw new IllegalArgumentException("엑셀에 중복된 코드값이 있습니다: "+codeId);
                result.add(new ProjectCriteriaManagementService.CodeValueInput(codeId.trim(),codeName.trim()));
            }
            if(result.isEmpty()) throw new IllegalArgumentException("등록할 코드 데이터가 없습니다");
            return List.copyOf(result);
        } catch(IllegalArgumentException e) {
            throw e;
        } catch(Exception e) {
            throw new IllegalArgumentException("코드 데이터 엑셀을 읽을 수 없습니다: "+e.getMessage(),e);
        }
    }

    private Header findHeader(Sheet sheet,DataFormatter formatter) {
        for(int r=sheet.getFirstRowNum();r<=Math.min(sheet.getLastRowNum(),10);r++){
            Row row=sheet.getRow(r);
            if(row==null) continue;
            Map<String,Integer> columns=new LinkedHashMap<>();
            for(int c=0;c<row.getLastCellNum();c++) columns.put(normalize(formatter.formatCellValue(row.getCell(c))),c);
            int codeId=find(columns,"코드","코드값","CDID","CODEID");
            int codeName=find(columns,"코드명","CDNM","CODENAME");
            if(codeId>=0&&codeName>=0)
                return new Header(r,find(columns,"검증코드명","코드규칙명","CDRULENM","RULENAME"),codeId,codeName);
        }
        throw new IllegalArgumentException("헤더를 찾을 수 없습니다. 필수 열: 코드(또는 CD_ID), 코드명(또는 CD_NM)");
    }

    private int find(Map<String,Integer> columns,String... aliases) {
        for(String alias:aliases){
            Integer value=columns.get(normalize(alias));
            if(value!=null) return value;
        }
        return -1;
    }
    private String normalize(String value){return value==null?"":value.replaceAll("[\\s_\\-]","").toUpperCase(Locale.ROOT);}
    private String value(Row row,int column,DataFormatter formatter){
        return column<0?"":formatter.formatCellValue(row.getCell(column)).trim();
    }
    private record Header(int row,int ruleName,int codeId,int codeName){}
    public record ImportResult(String ruleName,int importedCount){}
}
