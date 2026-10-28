package com.example.app;

import org.springframework.boot.SpringApplication;

public class TestNomeDoProjetoApplication {

    public static void main(String[] args) {
        SpringApplication.from(NomeDoProjetoApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
