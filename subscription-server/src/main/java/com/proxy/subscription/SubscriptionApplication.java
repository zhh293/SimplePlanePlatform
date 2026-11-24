package com.proxy.subscription;

import com.authsystem.sso.client.starter.SsoHttpSecurityConfigurer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@EnableScheduling
public class SubscriptionApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubscriptionApplication.class, args);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, SsoHttpSecurityConfigurer sso) throws Exception {
        http.with(sso, Customizer.withDefaults())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/device/**"))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/sso/login", "/sso/callback", "/sso/logout",
                    "/error", "/subscription.css", "/device/**").permitAll()
                .requestMatchers("/account/**", "/admin/**").authenticated().anyRequest().denyAll());
        return http.build();
    }
}
