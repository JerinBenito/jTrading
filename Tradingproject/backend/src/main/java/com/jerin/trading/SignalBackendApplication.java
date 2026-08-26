package com.jerin.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SignalBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SignalBackendApplication.class, args);
	}

}
