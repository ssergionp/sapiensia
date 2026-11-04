package br.com.sergio.sapiensia.security;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** One clock for time-based rules, so tests can replace it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
