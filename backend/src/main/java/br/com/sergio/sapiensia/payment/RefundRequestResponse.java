package br.com.sergio.sapiensia.payment;

import java.time.Instant;

/** When the Refund of the Order was asked: the new request, or the stored one when it already existed. */
record RefundRequestResponse(Instant requestedAt) {}
