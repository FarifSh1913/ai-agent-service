package ru.lordfarif.aiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.config.DocumentServiceProperties;

@SpringBootApplication
@EnableConfigurationProperties({OpenAiProperties.class, DocumentServiceProperties.class})
public class AiAgentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAgentServiceApplication.class, args);
    }
}
