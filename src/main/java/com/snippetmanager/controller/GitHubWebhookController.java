package com.snippetmanager.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snippetmanager.model.Snippet;
import com.snippetmanager.repository.SnippetRepository;
import com.snippetmanager.service.GitHubSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class GitHubWebhookController {

    private final GitHubSyncService gitHubSyncService;
    private final SnippetRepository snippetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubWebhookController(GitHubSyncService gitHubSyncService, SnippetRepository snippetRepository) {
        this.gitHubSyncService = gitHubSyncService;
        this.snippetRepository = snippetRepository;
    }

    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> handleGitHubPush(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawPayload) {

        if (!gitHubSyncService.verifyWebhookSignature(rawPayload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid webhook signature"));
        }

        if (!"push".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("status", "Ignored non-push event"));
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode headCommit = root.path("head_commit");
            String commitMessage = headCommit.path("message").asText("");

            if (commitMessage.startsWith(GitHubSyncService.SYNC_COMMIT_PREFIX)) {
                return ResponseEntity.ok(Map.of("status", "Ignored self-sync commit"));
            }

            String commitAuthor = headCommit.path("author").path("name").asText("GitHub Collaborator");
            int updatedCount = 0;

            for (JsonNode commit : root.path("commits")) {
                for (JsonNode pathNode : commit.path("added")) {
                    updatedCount += upsertFromGitHub(pathNode.asText(), commitAuthor);
                }
                for (JsonNode pathNode : commit.path("modified")) {
                    updatedCount += upsertFromGitHub(pathNode.asText(), commitAuthor);
                }
                for (JsonNode pathNode : commit.path("removed")) {
                    String removedPath = pathNode.asText();
                    snippetRepository.findByGithubPath(removedPath).ifPresent(snippetRepository::delete);
                }
            }

            return ResponseEntity.ok(Map.of(
                    "status", "Synced from GitHub",
                    "updatedSnippets", String.valueOf(updatedCount)
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Failed to process webhook: " + ex.getMessage()));
        }
    }

    private int upsertFromGitHub(String path, String author) {
        if (!path.startsWith("snippets/")) {
            return 0;
        }
        Map<String, String> fileData = gitHubSyncService.fetchFileFromGitHub(path);
        if (fileData == null) {
            return 0;
        }

        Snippet snippet = snippetRepository.findByGithubPath(path).orElseGet(() -> {
            Snippet s = new Snippet();
            s.setGithubPath(path);
            s.setTitle(gitHubSyncService.extractTitleFromPath(path));
            s.setLanguage(gitHubSyncService.inferLanguageFromPath(path));
            s.setCategory("GitHub Team Sync");
            s.setProjectContext("GitHub Collaboration");
            s.setVisibility("PUBLIC");
            s.setTags("github-sync");
            s.setDescription("Synced from GitHub: " + path);
            s.setSharedWithTeam(true);
            return s;
        });

        snippet.setCodeContent(fileData.get("content"));
        snippet.setGithubSha(fileData.get("sha"));
        snippet.setAuthor(author);
        snippetRepository.save(snippet);
        return 1;
    }
}
