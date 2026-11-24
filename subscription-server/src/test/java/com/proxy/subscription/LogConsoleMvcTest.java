package com.proxy.subscription;

import jakarta.servlet.ServletContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(LogConsoleMvcTest.Config.class)
class LogConsoleMvcTest {
    @Configuration
    @EnableWebMvc
    static class Config {
        @Bean
        LogSearchService logs() {
            var logs = mock(LogSearchService.class);
            when(logs.permitted("fixture-admin")).thenReturn(true);
            return logs;
        }

        @Bean
        ConsoleController controller(LogSearchService logs) {
            var ops = mock(ConsoleService.class);
            var access = mock(AccountController.class);
            when(access.requireAdmin()).thenReturn("fixture-admin");
            when(ops.user("alice")).thenReturn(Map.of("username", "alice", "subject_id", "alice"));
            when(ops.nodes()).thenReturn(List.of(Map.of("node_id", "fixture", "label", "Fixture Node",
                    "host", "example.test", "port", 443, "group_labels", "", "enabled", true)));
            return new ConsoleController(ops, access, logs);
        }

        @Bean
        ThymeleafViewResolver templates(ServletContext context) {
            var files = new ClassLoaderTemplateResolver();
            files.setPrefix("templates/");
            files.setSuffix(".html");
            files.setCharacterEncoding("UTF-8");
            var engine = new SpringTemplateEngine();
            engine.setTemplateResolver(files);
            var resolver = new ThymeleafViewResolver();
            resolver.setServletContext(context);
            resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");
            return resolver;
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired LogSearchService logs;

    @Test void rendersSelectedUserNodeAndCsrfWithoutLeakingWorkspaceIntoOtherPages() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/admin/logs").param("subject", "alice").param("node", "fixture")
                .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("data-endpoint=\"/admin/logs\"")))
                .andExpect(content().string(containsString("data-subject=\"alice\"")))
                .andExpect(content().string(containsString("data-csrf=\"test-csrf\"")))
                .andExpect(content().string(containsString("Fixture Node")))
                .andExpect(content().string(containsString("/admin/logs.js")));
        mvc.perform(get("/admin/grants")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"log-query\""))));
    }

    @Test void previewUsesSeparateRoutesAcrossPageAndApi() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/admin/logs-preview")).andExpect(status().isNotFound());
        when(logs.preview()).thenReturn(true);
        try {
            mvc.perform(get("/admin/logs")).andExpect(status().isNotFound());
            mvc.perform(get("/admin/logs/users").param("q", "alice")).andExpect(status().isNotFound());
            mvc.perform(get("/admin/logs-preview")
                            .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-endpoint=\"/admin/logs-preview\"")))
                    .andExpect(content().string(containsString("此页显示测试日志")));
        } finally {
            when(logs.preview()).thenReturn(false);
        }
    }
}
