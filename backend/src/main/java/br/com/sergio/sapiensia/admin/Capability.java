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
    PRODUCTS_PRICE,
    /**
     * Upload or replace the PDF of a Product (#18): separate from editing the text, because the file is what the
     * Customer receives and a future profile may fix the text without being able to swap the delivery.
     */
    PRODUCTS_FILE,
    /**
     * Publish and unpublish a Product (#19): separate from editing, because publishing is what puts a Product in front
     * of Customers.
     */
    PRODUCTS_PUBLISH,
    /** Read the list of Orders (#37): apart from the Product ones, so a future profile can run the catalog without seeing who bought. */
    ORDERS_VIEW;

    public String authority() {
        return "CAPABILITY_" + name();
    }
}
