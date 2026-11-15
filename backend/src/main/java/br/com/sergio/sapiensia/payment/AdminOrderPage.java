package br.com.sergio.sapiensia.payment;

import java.util.List;

/** One page of the Administrator's list of Orders. */
record AdminOrderPage(List<AdminOrderItem> items, int page, int totalPages) {}
