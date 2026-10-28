package br.com.sergio.sapiensia;

import org.springframework.boot.SpringApplication;

public class TestSapiensiaApplication {

    public static void main(String[] args) {
        SpringApplication.from(SapiensiaApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
