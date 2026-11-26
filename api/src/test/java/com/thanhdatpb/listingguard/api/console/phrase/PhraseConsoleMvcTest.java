package com.thanhdatpb.listingguard.api.console.phrase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.console.ConsoleFeedbackSupport;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningProperties;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningResponse;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningResponse.PhraseRowView;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningResponse.PhraseFindingView;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult.SourceLinkView;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult.CatalogStatus;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.Source;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.PhraseSummary;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.Verdict;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

@SpringJUnitWebConfig(PhraseConsoleMvcTest.Config.class)
class PhraseConsoleMvcTest {
    private static final String PATH = "/console/phrase-screening";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    @Autowired WebApplicationContext context;
    @Autowired PhraseScreeningService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void guessesFromTheDemoAndRendersVietnameseAndEnglish() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("..", "docs", "samples", "phrase-screening-sample.csv"));
        var file = new MockMultipartFile("file", "sample.csv", "text/csv", sample);
        String vi = mvc.perform(multipart(PATH + "/preview").file(file).param("mode", "file").locale(Locale.forLanguageTag("vi")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(vi).contains("Kiểm keyword", "Đã đoán cột", "id=\"phrase-counts\"",
                "value=\"0\" selected=\"selected\">Keyword Phrase", "value=\"1\" selected=\"selected\">Search Volume");
        String en = mvc.perform(get(PATH).locale(Locale.ENGLISH)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(en).contains("Screen keywords", "Paste a phrase list");
        var spreadsheet = new MockMultipartFile("file", "demo.xlsx", "application/octet-stream", new byte[]{1});
        mvc.perform(multipart(PATH + "/preview").file(spreadsheet).param("mode", "file"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsManualMappingRemembersHeadersAndExpiresExportsBySession() throws Exception {
        when(service.screenConsole(eq(TENANT), eq(ACCOUNT), any())).thenReturn(new PhraseScreeningResponse("fingerprint",
                "ETSY", "trademark-risk-2026-12", null, CatalogStatus.SERVED, Completeness.INCOMPLETE,
                new PhraseSummary(0, 0, 3, 0, 0, 1), List.of(
                        row("low volume", 1L, Completeness.COMPLETE),
                        row("=formula", 1800L, Completeness.COMPLETE),
                        row("incomplete", 9999L, Completeness.INCOMPLETE)), "Synthetic reference warning"));
        MockHttpSession session = new MockHttpSession();
        String location = mvc.perform(post(PATH + "/run").session(session).param("mode", "file")
                        .param("data", "Clicks,Words\n\"1,800\",=formula\n1,low volume\n9999,incomplete")
                        .param("phraseColumn", "1").param("volumeColumn", "0").param("remember", "true"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        verify(service).screenConsole(eq(TENANT), eq(ACCOUNT), org.mockito.ArgumentMatchers.argThat(request ->
                request.phrases().getFirst().text().equals("=formula") && request.phrases().getFirst().searchVolume() == 1800L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INSERT INTO console_phrase_mapping"), eq(ACCOUNT),
                eq("Words"), eq("Clicks"));

        String rendered = mvc.perform(get(location).session(session).param("tab", "CLEAR").locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(rendered).contains("Clear (3)", "Incomplete coverage");
        assertThat(rendered.indexOf("=formula")).isLessThan(rendered.indexOf("low volume"));
        String csv = mvc.perform(get(location + "/export").param("kind", "safe").session(session).locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo("\ufeffPhrase,Search volume\r\n'=formula,1800\r\nlow volume,1\r\n");
        String full = mvc.perform(get(location + "/export").session(session).locale(Locale.ENGLISH)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(full).startsWith("\ufeffPhrase,Search volume,Verdict,Mark,Rule,Risk,Source,Serial,Source link")
                .contains("incomplete,9999,CLEAR");
        mvc.perform(get(location + "/export").session(new MockHttpSession())).andExpect(status().isGone());
        when(clock.instant()).thenReturn(NOW.plus(PhraseConsoleResults.TTL));
        mvc.perform(get(location + "/export").session(session)).andExpect(status().isGone());
    }

    @Test
    void rendersTheSharedFeedbackWidgetAndEscapesFindingText() throws Exception {
        var finding = new PhraseFindingView("catalog_exact", RiskLevel.HIGH, Source.CATALOG, "Zorvex <script>",
                MatchType.EXACT, List.of(), null, 1L, List.of("uspto:12345678"),
                List.of(new SourceLinkView("uspto:12345678", "USPTO TSDR", "https://tsdr.uspto.gov/#caseNumber=12345678")),
                "phrase", 0, 6, List.of(), null);
        var row = new PhraseRowView("Zorvex", 100L, Verdict.BLOCK, Completeness.COMPLETE, false, List.of(),
                List.of(), List.of(finding));
        when(service.screenConsole(eq(TENANT), eq(ACCOUNT), any())).thenReturn(new PhraseScreeningResponse("fingerprint",
                "ETSY", "trademark-risk-2026-12", null, CatalogStatus.SERVED, Completeness.COMPLETE,
                new PhraseSummary(1, 0, 0, 0, 0, 0), List.of(row), "Synthetic reference warning"));
        var session = new MockHttpSession();
        var location = mvc.perform(post(PATH + "/run").session(session).param("data", "Zorvex"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        String result = mvc.perform(get(location).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result).contains(location + "/feedback", "value=\"CORRECT\"", "value=\"FALSE_POSITIVE\"",
                "Zorvex &lt;script&gt;", "https://tsdr.uspto.gov/#caseNumber=12345678").doesNotContain("Zorvex <script>",
                "value=\"NOT_USEFUL\"");
        mvc.perform(post(location + "/feedback").session(session).header("HX-Request", "true")
                        .param("position", "0").param("verdict", "CORRECT"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsOnlyWellFormedVolumes() {
        assertThat(PhraseInputTable.parseVolume(" 1,234,567 ")).isEqualTo(1_234_567L);
        for (String bad : List.of("-", "=2+3", "1,20", "1.2", "92333720368547758070")) {
            assertThat(PhraseInputTable.parseVolume(bad)).isNull();
        }
    }

    private static PhraseRowView row(String text, long volume, Completeness coverage) {
        return new PhraseRowView(text, volume, Verdict.CLEAR, coverage, coverage == Completeness.INCOMPLETE,
                List.of(), List.of(), List.of());
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

        @Bean ThymeleafViewResolver thymeleafViewResolver(WebApplicationContext context, MessageSource messages) {
            var resolver = new SpringResourceTemplateResolver();
            resolver.setApplicationContext(context);
            resolver.setPrefix("classpath:/templates/");
            resolver.setSuffix(".html");
            resolver.setCharacterEncoding("UTF-8");
            var engine = new SpringTemplateEngine();
            engine.setMessageSource(messages);
            engine.setTemplateResolver(resolver);
            var views = new ThymeleafViewResolver();
            views.setCharacterEncoding("UTF-8");
            views.setTemplateEngine(engine);
            views.setStaticVariables(Map.of("assetVersion", "test", "phraseScreeningEnabled", true));
            return views;
        }

        @Bean Clock clock() { return mock(Clock.class); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean PhraseScreeningService phraseService() { return mock(PhraseScreeningService.class); }

        @Bean PhraseConsoleController controller(PhraseScreeningService service, JdbcTemplate jdbc, Clock clock, MessageSource messages) {
            var support = mock(ConsoleFeedbackSupport.class);
            when(support.accountId(any())).thenReturn(Optional.of(ACCOUNT));
            org.mockito.Mockito.doAnswer(call -> {
                ((org.springframework.ui.Model) call.getArgument(0)).addAttribute("feedbackEnabled", true);
                return null;
            }).when(support).populate(any(), any(), any());
            var rulesets = mock(TrademarkRulesets.class);
            when(rulesets.ruleset()).thenReturn(TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-12.yaml"));
            return new PhraseConsoleController(service, new PhraseScreeningProperties(true, 2000, 200, 20_000),
                    rulesets, new PhraseConsoleResults(clock), support, mock(FindingFeedbackService.class),
                    new ConsoleMessages(messages), jdbc);
        }

        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == ConsoleUser.class;
                }

                @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer view,
                                                          NativeWebRequest request, WebDataBinderFactory binder) {
                    return new ConsoleUser("seller@example.test", TENANT);
                }
            });
        }
    }
}
