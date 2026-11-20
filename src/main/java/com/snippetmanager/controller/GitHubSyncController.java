package com.snippetmanager.controller;

import com.snippetmanager.repository.SnippetRepository;
import com.snippetmanager.service.GitHubSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/github")
public class GitHubSyncController {

    private final GitHubSyncService gitHubSyncService;
    private final SnippetRepository snippetRepository;

    public GitHubSyncController(GitHubSyncService gitHubSyncService, SnippetRepository snippetRepository) {
        this.gitHubSyncService = gitHubSyncService;
        this.snippetRepository = snippetRepository;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(gitHubSyncService.getSyncStatus());
    }

    @PostMapping("/push-all")
    public ResponseEntity<Map<String, Object>> pushAll() {
        return ResponseEntity.ok(gitHubSyncService.pushAllToGitHub(snippetRepository));
    }

    @PostMapping("/pull-all")
    public ResponseEntity<Map<String, Object>> pullAll() {
        return ResponseEntity.ok(gitHubSyncService.pullAllFromGitHub(snippetRepository));
    }
}
