package br.com.sergio.sapiensia.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "customers")
class Customer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_confirmed_at")
    private Instant emailConfirmedAt;

    // starts at the database default and is only incremented in SQL (password change)
    @Column(name = "session_version", insertable = false, updatable = false)
    private int sessionVersion;

    // filled by the database default
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {}

    Customer(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
