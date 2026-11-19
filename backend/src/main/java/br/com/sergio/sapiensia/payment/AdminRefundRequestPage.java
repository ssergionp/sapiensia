package br.com.sergio.sapiensia.payment;

import java.util.List;

/** One page of the Administrator's list of Refund requests. */
record AdminRefundRequestPage(List<AdminRefundRequestItem> items, int page, int totalPages) {}
