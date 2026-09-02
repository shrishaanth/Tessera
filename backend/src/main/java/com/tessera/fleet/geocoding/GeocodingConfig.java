package com.tessera.fleet.geocoding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeocodingConfig {

    @Bean
    @ConditionalOnMissingBean(UrlFetcher.class)
    public UrlFetcher urlFetcher() {
        return new UrlFetcher.HttpUrlFetcher();
    }
}
