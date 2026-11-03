package br.com.sergio.sapiensia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SapiensiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SapiensiaApplication.class, args);
    }
}
