package com.thanhdatpb.listingguard.api.console;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
@ConfigurationPropertiesBinding
public class ConsoleAuthModeConverter implements Converter<String, ConsoleAuthMode> {
    @Override
    public ConsoleAuthMode convert(String source) {
        return ConsoleAuthMode.valueOf(source.strip().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
    }
}
