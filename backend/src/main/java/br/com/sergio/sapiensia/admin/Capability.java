package br.com.sergio.sapiensia.admin;

/**
 * What an Administrator may do in the panel. Each panel ticket adds its capability here and its rule in
 * SecurityConfig; routes are opened by capability, never by a role name.
 */
public enum Capability {
    PANEL_ACCESS;

    public String authority() {
        return "CAPABILITY_" + name();
    }
}
