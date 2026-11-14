package br.com.sergio.sapiensia.library;

import java.time.Instant;
import java.util.UUID;

/** One Product of the Library; no price, Order or Product status (FR-029). */
record LibraryItem(UUID productId, String title, Instant grantedAt, String coverVersion) {}
