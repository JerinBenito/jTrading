package com.jtrade.registration_service.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "client_master_table")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long clientId;

    @Column(nullable = false)
    private String clientName;

    @Column(nullable = false, unique = true)
    private String clientEmailId;

    @Column(nullable = false)
    private String clientPhno;

    private String clientAddress;

    @Column(nullable = false, unique = true)
    private String clientUserName;

    @Column(nullable = false)
    private String clientUserPassword;

    private LocalDateTime createdDate = LocalDateTime.now();
}
