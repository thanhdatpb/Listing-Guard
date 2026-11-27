package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.capabilities.Capabilities;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

@SpringJUnitWebConfig(RoleHomeTemplateTest.Config.class)
class RoleHomeTemplateTest {
    @Autowired WebApplicationContext context;

    @Test
    void rendersBothLocalesAndEscapesCompanyDataInPrint() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String vi = mvc.perform(get("/manager").locale(Locale.forLanguageTag("vi")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(vi).contains("Tổng quan", "7 ngày", "30 ngày", "90 ngày", "aria-current=\"true\"",
                "manager-weekly-table", "40,0%", "&lt;xss&gt;").doesNotContain("<xss>");
        String en = mvc.perform(get("/print").locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(en).contains("Manager report", "lg-manager-print", "Print / save PDF", "Generated 2026-11-27 12:30 UTC",
                "40.0%", "pilot.csv", "&lt;xss&gt;").doesNotContain("<xss>", "id=\"pilot-daily\"");
        String seller = mvc.perform(get("/member").locale(Locale.forLanguageTag("vi")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(seller).contains("Việc của tôi", "Kiểm keyword", "Sửa listing", "Ẩn hướng dẫn", "Chưa có lượt kiểm",
                "id=\"pilot-start\"", "id=\"home-phrase-screening\"", "id=\"pilot-heading\">Pilot", "data-pack=\"listing-rules\"")
                .doesNotContain("manager-report-link");
    }

    @Test
    void hasA4PrintStylesAndUsesSharedThemeTokens() throws Exception {
        try (var css = getClass().getResourceAsStream("/static/console/role-home.css")) {
            String styles = new String(css.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(styles).contains("@media print", "size: A4 portrait", ".pilot-screen { display: none; }",
                    "var(--brand-text)", "var(--space-2)", "body:has(.lg-role-home) > #demo-banner { position: static; }")
                    .doesNotContainPattern("#[\\da-fA-F]{6}\\b").doesNotContain("gradient(");
        }
    }

    @Configuration
    @EnableWebMvc
    static class Config implements WebMvcConfigurer {
        @Bean MessageSource messageSource() {
            var source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            return source;
        }

        @Bean ConsoleMessages consoleMessages(MessageSource source) {
            return new ConsoleMessages(source);
        }

        @Bean ThymeleafViewResolver thymeleaf(WebApplicationContext context, MessageSource messages) {
            var resolver = new SpringResourceTemplateResolver();
            resolver.setApplicationContext(context);
            resolver.setPrefix("classpath:/templates/");
            resolver.setSuffix(".html");
            resolver.setCharacterEncoding("UTF-8");
            var engine = new SpringTemplateEngine();
            engine.setTemplateResolver(resolver);
            engine.setMessageSource(messages);
            var views = new ThymeleafViewResolver();
            views.setTemplateEngine(engine);
            views.setCharacterEncoding("UTF-8");
            var date = LocalDate.of(2026, 11, 27);
            var period = new ReportQuery(date.minusDays(29), date, null);
            var overview = new RoleHomeService.Overview(period, Instant.parse("2026-11-27T12:30:00Z"), 8, 3, 2, 1,
                    40L, 10L, 5, 0.4, 2, WeeklyChart.of(List.of(new WeeklyChart.Week("2026-W48", "23/11", true, 8, 3))),
                    List.of(), List.of(new RoleHomeService.Mark("<xss>", true, 1)),
                    new HomeView.CheckData("READY", "demo-catalog", "Fixture", date, 1, null, null, List.of()), false);
            var capabilities = new Capabilities(List.of(new Capabilities.PackCapability("listing-rules", true, Map.of())),
                    null, null, new Capabilities.FixesCapability(true), null);
            views.setStaticVariables(Map.of("pilotCapabilities", capabilities, "assetVersion", "test", "phraseScreeningEnabled", true,
                    "overview", overview, "periods", List.of(7, 30, 90),
                    "work", new RoleHomeService.Work(List.of(), List.of(), false)));
            return views;
        }

        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addViewController("/manager").setViewName("console/manager-home");
            registry.addViewController("/member").setViewName("console/member-home");
            registry.addViewController("/print").setViewName("console/pilot-report");
        }
    }
}
