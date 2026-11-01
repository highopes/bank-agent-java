package com.demo.bankagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "demo_customer")
public class DemoCustomer {

    @Id
    private String id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String segment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected DemoCustomer() {
    }

    public DemoCustomer(String id, String displayName, String segment, LocalDateTime createdAt) {
        this.id = id;
        this.displayName = displayName;
        this.segment = segment;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSegment() {
        return segment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
