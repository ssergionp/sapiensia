package br.com.sergio.sapiensia.customer;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Task data of the new-device e-mail: no e-mail address and no device token ever reach the scheduler table. */
record NewDeviceNotice(UUID customerId, Instant loggedInAt) implements Serializable {}
