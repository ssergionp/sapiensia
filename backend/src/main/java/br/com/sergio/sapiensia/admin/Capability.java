package br.com.sergio.sapiensia.admin;

/**
 * What an Administrator may do in the panel. Each panel ticket adds its capability here and its rule in
 * SecurityConfig; routes are opened by capability, never by a role name.
 */
public enum Capability {
    PANEL_ACCESS,
    /** Generate a new set of recovery codes (#16); the route also asks for a fresh TOTP code. */
    RECOVERY_CODES_MANAGE,
    /** Create, list, view and edit the text of Products (#17); the initial price is part of creating. */
    PRODUCTS_EDIT,
    /** Change the price of an existing Product (#17, SEC-029): separate from editing, so money has its own lock. */
    PRODUCTS_PRICE;

    public String authority() {
        return "CAPABILITY_" + name();
    }
}
