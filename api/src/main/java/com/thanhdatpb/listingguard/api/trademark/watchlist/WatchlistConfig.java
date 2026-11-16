package com.thanhdatpb.listingguard.api.trademark.watchlist;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetException;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Loads the watchlist settings (ADR-031) at start-up: a file that cannot be read or parsed stops the application. */
@Configuration
@EnableConfigurationProperties(WatchlistProperties.class)
public class WatchlistConfig {

    @Bean
    WatchlistSettings watchlistSettings(WatchlistProperties properties, ResourceLoader resources) {
        try {
            return WatchlistSettings.parse(resources.getResource(properties.settings()).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TrademarkRulesetException("Cannot read the watchlist settings " + properties.settings(), e);
        } catch (TrademarkRulesetException e) {
            throw new TrademarkRulesetException("Watchlist settings " + properties.settings() + ": " + e.getMessage(), e);
        }
    }
}
