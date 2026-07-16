package io.github.seoleeder.owls_pick.global.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "owls-pick.scheduler")
public record SchedulerProperties(
        boolean enabled
) {
}
