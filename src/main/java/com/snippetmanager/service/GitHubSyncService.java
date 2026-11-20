package com.snippetmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snippetmanager.model.Snippet;
import com.snippetmanager.repository.SnippetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class GitHubSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubSyncService.class);
    public static final String SYNC_COMMIT_PREFIX = "[SnippetVault-Sync]";

    @Value("${github.sync.enabled:false}")
    private boolean syncEnabled;

    @Value("${github.token:}")
    private String githubToken;

    @Value("${github.repo.owner:}")
    private String repoOwner;

    @Value("${github.repo.name:}")
    private String repoName;

    @Value("${github.repo.branch:main}")
    private String repoBranch;

    @Value("${github.webhook.secret:}")
    private String webhookSecret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return syncEnabled
                && githubToken != null && !githubToken.isBlank()
                && repoOwner != null && !repoOwner.isBlank()
                && repoName != null && !repoName.isBlank();
    }

    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", isEnabled());
        status.put("repo", isEnabled() ? (repoOwner + "/" + repoName) : "Not configured");
        status.put("branch", repoBranch);
        return status;
    }

    public boolean pushSnippetToGitHub(Snippet snippet) {
        if (!isEnabled() || !isSyncableVisibility(snippet)) {
            return false;
        }
        try {
            String path = snippet.getGithubPath();
            if (path == null || path.isBlank()) {
                path = buildGithubPath(snippet);
                snippet.setGithubPath(path);
            }

            String url = String.format("https://api.github.com/repos/%s/%s/contents/%s",
                    repoOwner, repoName, path);

            // Always fetch current SHA from GitHub if not cached to prevent 409/422 conflicts
            String sha = snippet.getGithubSha();
            if (sha == null || sha.isBlank()) {
                sha = fetchCurrentSha(url);
                if (sha != null) snippet.setGithubSha(sha);
            }

            Map<String, Object> body = new HashMap<>();
            body.put("message", SYNC_COMMIT_PREFIX + " Save: " + snippet.getTitle());
            body.put("content", Base64.getEncoder().encodeToString(
                    snippet.getCodeContent().getBytes(StandardCharsets.UTF_8)));
            body.put("branch", repoBranch);
            if (sha != null && !sha.isBlank()) {
                body.put("sha", sha);
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("content") && root.get("content").has("sha")) {
                    snippet.setGithubSha(root.get("content").get("sha").asText());
                }
                return true;
            }
        } catch (Exception ex) {
            log.warn("GitHub outbound sync failed for '{}': {}", snippet.getTitle(), ex.getMessage());
        }
        return false;
    }

    public void deleteSnippetFromGitHub(Snippet snippet) {
        if (!isEnabled() || snippet.getGithubPath() == null || snippet.getGithubPath().isBlank()) {
            return;
        }
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/contents/%s",
                    repoOwner, repoName, snippet.getGithubPath());

            String sha = fetchCurrentSha(url);
            if (sha == null) return;

            Map<String, Object> body = new HashMap<>();
            body.put("message", SYNC_COMMIT_PREFIX + " Delete: " + snippet.getTitle());
            body.put("sha", sha);
            body.put("branch", repoBranch);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, createAuthHeaders());
            restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
        } catch (Exception ex) {
            log.warn("GitHub delete sync failed for '{}': {}", snippet.getGithubPath(), ex.getMessage());
        }
    }

    /**
     * Push ALL eligible snippets (GLOBAL/PUBLIC) from MySQL into the GitHub repository.
     */
    public Map<String, Object> pushAllToGitHub(SnippetRepository snippetRepository) {
        if (!isEnabled()) {
            return Map.of("status", "disabled", "message", "GitHub sync is not enabled in .env");
        }
        List<Snippet> all = snippetRepository.findAll();
        int pushed = 0;
        int failed = 0;
        for (Snippet s : all) {
            if (!isSyncableVisibility(s)) continue;
            if (pushSnippetToGitHub(s)) {
                snippetRepository.save(s);
                pushed++;
            } else {
                failed++;
            }
        }
        return Map.of("status", "ok", "pushed", pushed, "failed", failed, "repo", repoOwner + "/" + repoName);
    }

    /**
     * Pull ALL files under snippets/ from GitHub and upsert them into MySQL.
     */
    public Map<String, Object> pullAllFromGitHub(SnippetRepository snippetRepository) {
        if (!isEnabled()) {
            return Map.of("status", "disabled", "message", "GitHub sync is not enabled in .env");
        }
        int synced = 0;
        try {
            String treeUrl = String.format("https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1",
                    repoOwner, repoName, repoBranch);
            HttpEntity<Void> req = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<String> res = restTemplate.exchange(treeUrl, HttpMethod.GET, req, String.class);

            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                JsonNode tree = objectMapper.readTree(res.getBody()).path("tree");
                for (JsonNode item : tree) {
                    String type = item.path("type").asText("");
                    String path = item.path("path").asText("");
                    String sha = item.path("sha").asText("");
                    if (!"blob".equals(type) || !path.startsWith("snippets/")) {
                        continue;
                    }
                    Optional<Snippet> existingOpt = snippetRepository.findByGithubPath(path);
                    if (existingOpt.isPresent() && sha.equals(existingOpt.get().getGithubSha())) {
                        continue; // already up to date
                    }
                    Map<String, String> fileData = fetchFileFromGitHub(path);
                    if (fileData == null) continue;

                    Snippet snippet = existingOpt.orElseGet(() -> {
                        Snippet s = new Snippet();
                        s.setGithubPath(path);
                        s.setTitle(extractTitleFromPath(path));
                        s.setLanguage(inferLanguageFromPath(path));
                        s.setCategory("GitHub Team Sync");
                        s.setProjectContext("GitHub Sync");
                        s.setVisibility("PUBLIC");
                        s.setTags("github-sync");
                        s.setDescription("Synced from GitHub: " + path);
                        s.setSharedWithTeam(true);
                        s.setAuthor("GitHub Collaborator");
                        return s;
                    });
                    snippet.setCodeContent(fileData.get("content"));
                    snippet.setGithubSha(fileData.get("sha"));
                    snippetRepository.save(snippet);
                    synced++;
                }
            }
            return Map.of("status", "ok", "pulled", synced, "repo", repoOwner + "/" + repoName);
        } catch (Exception ex) {
            log.warn("Pull all from GitHub failed: {}", ex.getMessage());
            return Map.of("status", "error", "message", ex.getMessage());
        }
    }

    public Map<String, String> fetchFileFromGitHub(String path) {
        if (!isEnabled()) return null;
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                    repoOwner, repoName, path, repoBranch);
            HttpEntity<Void> request = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode node = objectMapper.readTree(response.getBody());
                String encoded = node.get("content").asText().replaceAll("\\s+", "");
                String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                String sha = node.get("sha").asText();
                return Map.of("content", decoded, "sha", sha);
            }
        } catch (Exception ex) {
            log.warn("Failed to fetch file '{}' from GitHub: {}", path, ex.getMessage());
        }
        return null;
    }

    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(signatureHeader);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSyncableVisibility(Snippet s) {
        String vis = s.getVisibility() == null ? "PUBLIC" : s.getVisibility().toUpperCase();
        return s.isSharedWithTeam() && ("GLOBAL".equals(vis) || "PUBLIC".equals(vis));
    }

    private String fetchCurrentSha(String url) {
        try {
            HttpEntity<Void> req = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<String> res = restTemplate.exchange(url + "?ref=" + repoBranch, HttpMethod.GET, req, String.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                return objectMapper.readTree(res.getBody()).get("sha").asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(githubToken);
        headers.setAccept(MediaType.parseMediaTypes("application/vnd.github+json"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public String buildGithubPath(Snippet snippet) {
        String langSlug = snippet.getLanguage().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        String titleSlug = snippet.getTitle().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String ext = switch (snippet.getLanguage().toLowerCase()) {
            case "java" -> "java";
            case "javascript" -> "js";
            case "python" -> "py";
            case "sql" -> "sql";
            case "html/css" -> "html";
            case "yaml" -> "yml";
            case "bash" -> "sh";
            case "dockerfile" -> "Dockerfile";
            default -> "txt";
        };
        if ("Dockerfile".equals(ext)) {
            return String.format("snippets/%s/%d-%s.Dockerfile", langSlug, snippet.getId(), titleSlug);
        }
        return String.format("snippets/%s/%d-%s.%s", langSlug, snippet.getId(), titleSlug, ext);
    }

    public String extractTitleFromPath(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return stem.replaceFirst("^\\d+-", "").replace('-', ' ');
    }

    public String inferLanguageFromPath(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) return "Java";
        if (lower.endsWith(".js")) return "JavaScript";
        if (lower.endsWith(".py")) return "Python";
        if (lower.endsWith(".sql")) return "SQL";
        if (lower.endsWith(".html") || lower.endsWith(".css")) return "HTML/CSS";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "YAML";
        if (lower.endsWith(".sh")) return "Bash";
        if (lower.contains("dockerfile")) return "Dockerfile";
        return "Java";
    }
}
