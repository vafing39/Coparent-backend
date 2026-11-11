package com.vynk.coparent_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CoparentBackendApplication {

    static void main(String[] args) {
        SpringApplication.run(CoparentBackendApplication.class, args);
    }

}
