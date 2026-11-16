package br.com.sergio.sapiensia.payment;

import java.util.List;

/** One page of the provider's charge listing; {@code hasMore} says whether a next page exists. */
public record PixChargePage(List<PixCharge> charges, boolean hasMore) {

    public PixChargePage {
        charges = List.copyOf(charges);
    }
}
