package br.com.sergio.sapiensia.payment;

import java.util.List;

/** The Customer's Orders, newest first (FR-020). */
record OrderListResponse(List<OrderSummary> items) {}
