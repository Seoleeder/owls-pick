package io.github.seoleeder.owls_pick;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan("io.github.seoleeder.owls_pick")
@EnableJpaRepositories(basePackages = "io.github.seoleeder.owls_pick.repository")
@SpringBootApplication
public class OwlsPickApplication {

	public static void main(String[] args) {
		SpringApplication.run(OwlsPickApplication.class, args);
	}

}
