package com.andrewcorp.webwallet.config;

import com.andrewcorp.webwallet.common.util.Zones;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** "Today", the current month and default periods follow Ukrainian dates. */
    @Bean
    public Clock clock() {
        return Clock.system(Zones.KYIV);
    }
}
