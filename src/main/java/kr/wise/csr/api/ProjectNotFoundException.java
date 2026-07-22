package kr.wise.csr.api;

public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException(long projectId) {
        super("프로젝트를 찾을 수 없습니다: " + projectId);
    }
}
