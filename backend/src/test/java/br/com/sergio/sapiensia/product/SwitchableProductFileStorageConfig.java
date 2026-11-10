package br.com.sergio.sapiensia.product;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the product file storage with {@link SwitchableProductFileStorage} in the tests that import it. */
@TestConfiguration
public class SwitchableProductFileStorageConfig {

    @Bean
    @Primary
    public SwitchableProductFileStorage switchableProductFileStorage(FileProductFileStorage disk) {
        return new SwitchableProductFileStorage(disk);
    }
}
