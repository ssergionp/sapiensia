package br.com.sergio.sapiensia.admin;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs the Administrator bootstrap once at every startup. */
@Component
class AdministratorBootstrapRunner implements ApplicationRunner {

    private final AdministratorBootstrap bootstrap;

    AdministratorBootstrapRunner(AdministratorBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.bootstrap();
    }
}
