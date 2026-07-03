package com.scanops.scan;

import com.scanops.github.GithubAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * GitHub 레포지토리 코드 스캔 서비스
 * GitHub API로 파일을 가져와 ScanOps Model API로 분석.
 *
 * <p>프라이빗 레포: 레포 주인이 ScanOps GitHub App을 설치했으면
 * {@link GithubAppService#tokenForRepo}가 그 레포 전용 단기 설치 토큰을 발급한다.
 * 설치가 없으면 공개 레포용 {@code github.token}(있으면)으로, 그것도 없으면 비인증 호출.
 * 사용자 PAT는 받지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GithubScanService {

    @Value("${github.token:}")
    private String githubToken;

    private final WebClient.Builder webClientBuilder;
    private final ScanopsModelClient modelClient;
    private final GithubAppService githubAppService;

    // 분석 대상 확장자
    private static final Set<String> TARGET_EXTENSIONS = Set.of(
            ".java", ".kt",
            ".jsx", ".tsx", ".js", ".ts",
            ".py", ".go", ".rs",
            ".c", ".cpp", ".h",
            ".php", ".rb",
            ".yml", ".yaml"
    );

    // 제외 디렉토리
    private static final Set<String> EXCLUDE_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", "target",
            "vendor", "__pycache__", ".idea", ".vscode"
    );

    // 언어 매핑 (PrScanController에서도 사용)
    static final Map<String, String> EXT_TO_LANG = Map.ofEntries(
            Map.entry(".java",  "Java Spring Boot"),
            Map.entry(".kt",    "Kotlin"),
            Map.entry(".jsx",   "React / Next.js"),
            Map.entry(".tsx",   "React / Next.js"),
            Map.entry(".js",    "Node.js / Express"),
            Map.entry(".ts",    "Node.js / Express"),
            Map.entry(".py",    "Python"),
            Map.entry(".go",    "Go"),
            Map.entry(".rs",    "Rust"),
            Map.entry(".c",     "C"),
            Map.entry(".cpp",   "C++"),
            Map.entry(".h",     "C"),
            Map.entry(".php",   "PHP"),
            Map.entry(".rb",    "Ruby"),
            Map.entry(".yml",   "GitHub Actions YAML"),
            Map.entry(".yaml",  "GitHub Actions YAML")
    );

    /** 파일 이름에서 언어 감지 */
    public String detectLanguage(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return null;
        return EXT_TO_LANG.get(filename.substring(dot).toLowerCase());
    }

    /**
     * GitHub URL에서 owner/repo 파싱
     * https://github.com/user/repo → ["user", "repo"]
     */
    public String[] parseRepoUrl(String url) {
        String path = url
                .replace("https://github.com/", "")
                .replace("http://github.com/", "")
                .replaceAll("\\.git$", "")
                .replaceAll("/$", "");
        String[] parts = path.split("/");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        return new String[]{parts[0], parts[1]};
    }

    /**
     * 레포지토리의 모든 분석 대상 파일 목록 조회 (GitHub Trees API)
     */
    public List<Map<String, Object>> listRepoFiles(String owner, String repo, String token) {
        String url = String.format("https://api.github.com/repos/%s/%s/git/trees/HEAD?recursive=1",
                owner, repo);

        WebClient client = buildGithubClient(token);
        Map<String, Object> tree;
        try {
            tree = client.get().uri(url).retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("GitHub API 호출 실패: " + e.getMessage());
        }

        if (tree == null) return List.of();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) tree.get("tree");
        if (items == null) return List.of();

        return items.stream()
                .filter(item -> "blob".equals(item.get("type")))
                .filter(item -> {
                    String path = (String) item.get("path");
                    if (path == null) return false;
                    // 제외 디렉토리 필터
                    for (String dir : EXCLUDE_DIRS) {
                        if (path.contains("/" + dir + "/") || path.startsWith(dir + "/")) return false;
                    }
                    // 확장자 필터
                    int dot = path.lastIndexOf('.');
                    if (dot < 0) return false;
                    return TARGET_EXTENSIONS.contains(path.substring(dot));
                })
                .limit(50) // 최대 50개 파일 (과부하 방지)
                .toList();
    }

    /**
     * 단일 파일 내용 가져오기 (GitHub Contents API)
     */
    public String fetchFileContent(String owner, String repo, String path, String token) {
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s",
                owner, repo, path);
        try {
            Map<String, Object> response = buildGithubClient(token).get().uri(url).retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            if (response == null) return "";
            String encoding = (String) response.get("encoding");
            String content  = (String) response.get("content");
            if ("base64".equals(encoding) && content != null) {
                return new String(Base64.getDecoder().decode(content.replace("\n", "")));
            }
            return "";
        } catch (Exception e) {
            log.warn("파일 가져오기 실패 {}: {}", path, e.getMessage());
            return "";
        }
    }

    /** 스캔 결과 + 파일 내용 맵 */
    public record ScanResult(
        ScanopsModelClient.BatchResult batch,
        Map<String, String> fileContents   // filePath → code
    ) {}

    /**
     * GitHub 레포 전체 분석 (파일 내용도 함께 반환)
     */
    public ScanResult scanRepo(String repoUrl) {
        String[] parts = parseRepoUrl(repoUrl);
        String owner = parts[0], repo = parts[1];

        // 레포 주인이 App을 설치했으면 그 레포 전용 단기 토큰(프라이빗 접근 가능). 없으면 null.
        String token = githubAppService.tokenForRepo(owner, repo);
        log.info("GitHub 레포 스캔 시작: {}/{} (설치토큰 {})", owner, repo, token != null ? "사용" : "없음");
        List<Map<String, Object>> files = listRepoFiles(owner, repo, token);
        log.info("분석 대상 파일: {}개", files.size());

        List<ScanopsModelClient.AnalyzeRequest> requests = new ArrayList<>();
        Map<String, String> fileContents = new java.util.LinkedHashMap<>();

        for (Map<String, Object> file : files) {
            String path = (String) file.get("path");
            int dot = path.lastIndexOf('.');
            String ext  = dot >= 0 ? path.substring(dot) : "";
            String lang = EXT_TO_LANG.getOrDefault(ext, "Unknown");

            String code = fetchFileContent(owner, repo, path, token);
            if (code.isBlank() || code.length() > 8000) continue;

            requests.add(new ScanopsModelClient.AnalyzeRequest(lang, code, path, true));
            fileContents.put(path, code);
        }

        if (requests.isEmpty()) {
            return new ScanResult(new ScanopsModelClient.BatchResult(0, 0, List.of(), 0), Map.of());
        }

        log.info("모델 분석 시작: {}개 파일", requests.size());
        return new ScanResult(modelClient.analyzeBatch(requests), fileContents);
    }

    /**
     * @param token 레포 전용 설치 토큰(프라이빗 접근). null이면 공개용 {@code github.token},
     *              그것도 없으면 비인증 호출.
     */
    private WebClient buildGithubClient(String token) {
        String auth = (token != null && !token.isBlank()) ? token
                : (githubToken != null && !githubToken.isBlank()) ? githubToken
                : null;
        WebClient.Builder builder = webClientBuilder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (auth != null) {
            builder.defaultHeader("Authorization", "Bearer " + auth);
        }
        return builder.build();
    }
}
