package com.snippetmanager.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snippetmanager.model.Snippet;
import com.snippetmanager.model.User;
import com.snippetmanager.repository.SnippetRepository;
import com.snippetmanager.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner seedDemoUsersAndSnippets(UserRepository userRepository, SnippetRepository snippetRepository) {
        return args -> {
            List<User> demoUsers = List.of(
                    new User("Abhishek Kumar Singh", "abhishek.singh", "1234", "Team Lead"),
                    new User("Achal", "achal", "1234", "Backend Dev"),
                    new User("Abhishek", "abhishek", "1234", "Frontend Dev"),
                    new User("Rohan Verma", "rohan", "1234", "Database Dev"),
                    new User("Priya Nair", "priya", "1234", "QA & Testing")
            );
            for (User u : demoUsers) {
                if (userRepository.findByUsernameIgnoreCase(u.getUsername()).isEmpty()) {
                    userRepository.save(u);
                }
            }

            if (snippetRepository.count() < 10) {
                try (InputStream is = new ClassPathResource("seed-snippets.json").getInputStream()) {
                    ObjectMapper mapper = new ObjectMapper();
                    List<Snippet> seeds = mapper.readValue(is, new TypeReference<List<Snippet>>() {});
                    for (Snippet s : seeds) {
                        s.setId(null);
                        snippetRepository.save(s);
                    }
                }
            }
        };
    }
}
