package br.com.sergio.sapiensia.admin;

import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** What other features may ask about Administrator sessions. */
@Service
public class AdministratorSessions {

    private final Administrators administrators;

    AdministratorSessions(Administrators administrators) {
        this.administrators = administrators;
    }

    /** The current session version of the Administrator, or empty if the account no longer exists. */
    public OptionalInt findSessionVersion(UUID id) {
        return administrators.findSessionVersion(id);
    }
}
