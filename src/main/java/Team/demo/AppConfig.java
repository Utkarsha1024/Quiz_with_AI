package Team.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * This class holds application-wide configuration for beans.
 * A "bean" is an object that Spring manages.
 */
@Configuration
public class AppConfig {

    /**
     * ✅ This method creates a RestTemplate object and registers it as a bean
     * in the Spring application context. Now, Spring can inject this bean
     * into other classes, like AiQuizService.
     *
     * @return A new instance of RestTemplate.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
