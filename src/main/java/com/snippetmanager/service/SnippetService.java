package com.snippetmanager.service;

import com.snippetmanager.dto.SnippetRequestDTO;
import com.snippetmanager.exception.ResourceNotFoundException;
import com.snippetmanager.model.Snippet;
import com.snippetmanager.repository.SnippetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SnippetService {

    private final SnippetRepository snippetRepository;
    private GitHubSyncService gitHubSyncService;

    public SnippetService(SnippetRepository snippetRepository) {
        this.snippetRepository = snippetRepository;
    }

    @Autowired(required = false)
    public void setGitHubSyncService(GitHubSyncService gitHubSyncService) {
        this.gitHubSyncService = gitHubSyncService;
    }

    public List<Snippet> getAllSnippets(String keyword, String language, String category) {
        boolean hasOperators = keyword != null && (keyword.contains(":") || keyword.trim().contains(" "));
        if (!hasOperators) {
            return snippetRepository.searchSnippets(keyword, language, category);
        }

        List<Snippet> baseList = snippetRepository.searchSnippets(null, language, category);
        String[] tokens = keyword.trim().toLowerCase().split("\\s+");

        return baseList.stream()
                .map(s -> new AbstractMap.SimpleEntry<>(s, scoreSnippet(s, tokens)))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private int scoreSnippet(Snippet s, String[] tokens) {
        int total = 0;
        String title = safeLower(s.getTitle());
        String lang = safeLower(s.getLanguage());
        String cat = safeLower(s.getCategory());
        String proj = safeLower(s.getProjectContext());
        String tags = safeLower(s.getTags());
        String desc = safeLower(s.getDescription());
        String code = safeLower(s.getCodeContent());
        String author = safeLower(s.getAuthor());

        for (String tok : tokens) {
            if (tok.startsWith("lang:")) {
                String val = tok.substring(5);
                if (!val.isEmpty() && !lang.contains(val)) return 0;
                total += 15;
            } else if (tok.startsWith("tag:")) {
                String val = tok.substring(4);
                if (!val.isEmpty() && !tags.contains(val)) return 0;
                total += 14;
            } else if (tok.startsWith("cat:")) {
                String val = tok.substring(4);
                if (!val.isEmpty() && !cat.contains(val)) return 0;
                total += 12;
            } else if (tok.startsWith("project:")) {
                String val = tok.substring(8);
                if (!val.isEmpty() && !proj.contains(val)) return 0;
                total += 14;
            } else if (tok.startsWith("author:")) {
                String val = tok.substring(7);
                if (!val.isEmpty() && !author.contains(val)) return 0;
                total += 10;
            } else {
                int tokenScore = 0;
                if (title.contains(tok)) tokenScore += 12;
                if (tags.contains(tok)) tokenScore += 10;
                if (proj.contains(tok)) tokenScore += 8;
                if (lang.contains(tok) || cat.contains(tok)) tokenScore += 6;
                if (desc.contains(tok)) tokenScore += 5;
                if (code.contains(tok)) tokenScore += 3;
                if (author.contains(tok)) tokenScore += 2;
                if (tokenScore == 0) return 0;
                total += tokenScore;
            }
        }
        return total;
    }

    private String safeLower(String val) {
        return val == null ? "" : val.toLowerCase();
    }

    public Snippet getSnippetById(Long id) {
        return snippetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Snippet not found with ID: " + id));
    }

    public Snippet getSnippetByShareToken(String shareToken) {
        return snippetRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("Shared snippet not found for token: " + shareToken));
    }

    public Snippet createSnippet(SnippetRequestDTO dto) {
        Snippet snippet = new Snippet();
        mapDtoToEntity(dto, snippet);
        Snippet saved = snippetRepository.save(snippet);
        if (gitHubSyncService != null) {
            gitHubSyncService.pushSnippetToGitHub(saved);
            saved = snippetRepository.save(saved);
        }
        return saved;
    }

    public Snippet updateSnippet(Long id, SnippetRequestDTO dto) {
        Snippet existing = getSnippetById(id);
        mapDtoToEntity(dto, existing);
        Snippet saved = snippetRepository.save(existing);
        if (gitHubSyncService != null) {
            gitHubSyncService.pushSnippetToGitHub(saved);
            saved = snippetRepository.save(saved);
        }
        return saved;
    }

    public Snippet forkSnippet(Long id, String newAuthor, String newProjectContext) {
        Snippet original = getSnippetById(id);
        Snippet forked = new Snippet();
        forked.setTitle(original.getTitle() + " (Fork)");
        forked.setLanguage(original.getLanguage());
        forked.setCategory(original.getCategory());
        forked.setProjectContext(newProjectContext != null && !newProjectContext.isBlank()
                ? newProjectContext : "New Project Adaptation");
        forked.setTags(original.getTags());
        forked.setDescription(original.getDescription());
        forked.setCodeContent(original.getCodeContent());
        forked.setAuthor(newAuthor != null && !newAuthor.isBlank() ? newAuthor : "Team Collaborator");
        forked.setFavorite(false);
        forked.setSharedWithTeam(true);
        Snippet saved = snippetRepository.save(forked);
        if (gitHubSyncService != null) {
            gitHubSyncService.pushSnippetToGitHub(saved);
            saved = snippetRepository.save(saved);
        }
        return saved;
    }

    public Snippet toggleFavorite(Long id) {
        Snippet existing = getSnippetById(id);
        existing.setFavorite(!existing.isFavorite());
        return snippetRepository.save(existing);
    }

    public Snippet incrementCopyCount(Long id) {
        Snippet existing = getSnippetById(id);
        existing.setCopyCount(existing.getCopyCount() + 1);
        return snippetRepository.save(existing);
    }

    public void deleteSnippet(Long id) {
        Snippet existing = getSnippetById(id);
        if (gitHubSyncService != null) {
            gitHubSyncService.deleteSnippetFromGitHub(existing);
        }
        snippetRepository.delete(existing);
    }

    public Map<String, Object> getDashboardStats() {
        List<Snippet> all = snippetRepository.findAll();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSnippets", all.size());
        stats.put("favoriteCount", all.stream().filter(Snippet::isFavorite).count());
        stats.put("sharedCount", all.stream().filter(Snippet::isSharedWithTeam).count());
        stats.put("totalCopies", all.stream().mapToInt(Snippet::getCopyCount).sum());
        Map<String, Long> byLanguage = all.stream()
                .collect(Collectors.groupingBy(Snippet::getLanguage, Collectors.counting()));
        stats.put("languageBreakdown", byLanguage);
        return stats;
    }

    private void mapDtoToEntity(SnippetRequestDTO dto, Snippet snippet) {
        snippet.setTitle(dto.getTitle());
        snippet.setLanguage(dto.getLanguage());
        snippet.setCategory(dto.getCategory());
        snippet.setProjectContext(dto.getProjectContext() != null && !dto.getProjectContext().isBlank()
                ? dto.getProjectContext() : "General Utility");
        snippet.setTags(dto.getTags());
        snippet.setDescription(dto.getDescription());
        snippet.setCodeContent(dto.getCodeContent());
        snippet.setAuthor(dto.getAuthor() != null && !dto.getAuthor().isBlank() ? dto.getAuthor() : "Team Developer");
        snippet.setFavorite(dto.isFavorite());
        snippet.setSharedWithTeam(dto.isSharedWithTeam());
    }
}
