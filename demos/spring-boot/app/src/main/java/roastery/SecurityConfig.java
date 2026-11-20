package roastery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Actuator's {@code /actuator/prometheus} is for the agent, which reads it on 127.0.0.1 with the credentials in
 * {@link AgentSettings}, as Basic Auth; nobody else gets in. The status page and the health check are open.
 */
@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) {
    return http.authorizeHttpRequests(
            requests -> requests.requestMatchers("/actuator/prometheus").authenticated().anyRequest().permitAll())
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean
  UserDetailsService metricsUser(AgentSettings settings) {
    if (!StringUtils.hasText(settings.metricsUser())) {
      return new InMemoryUserDetailsManager();
    }
    return new InMemoryUserDetailsManager(
        User.withUsername(settings.metricsUser())
            .password("{noop}" + settings.metricsPassword())
            .roles("METRICS")
            .build());
  }
}
