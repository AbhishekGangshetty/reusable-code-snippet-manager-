package com.snippetmanager.config;

import com.snippetmanager.model.User;
import com.snippetmanager.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner seedTeamUsers(UserRepository userRepository) {
        return args -> {
            List<User> defaults = List.of(
                    new User("Abhishek Kumar Singh", "abhishek.singh", "1234", "Team Lead"),
                    new User("Achal", "achal", "1234", "Backend Dev"),
                    new User("Abhishek", "abhishek", "1234", "Frontend Dev"),
                    new User("Rohan Verma", "rohan", "1234", "Database Dev"),
                    new User("Priya Nair", "priya", "1234", "QA & Testing"),
                    new User("Karthik Rao", "karthik", "1234", "DevOps")
            );
            for (User u : defaults) {
                if (userRepository.findByUsernameIgnoreCase(u.getUsername()).isEmpty()) {
                    userRepository.save(u);
                }
            }
        };
    }
}
