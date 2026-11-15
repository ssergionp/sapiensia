package br.com.sergio.sapiensia.admin;

import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Where messages to the Administrator go: other features use this class instead of the {@code administrators} table. */
@Component
public class AdministratorContact {

    private final Administrators administrators;

    AdministratorContact(Administrators administrators) {
        this.administrators = administrators;
    }

    /** The e-mail of the Administrator account (the oldest, if there were several); empty when none exists. */
    public Optional<String> findEmail() {
        return administrators.findOnly(Duration.ZERO).map(Administrators.Row::email);
    }
}
