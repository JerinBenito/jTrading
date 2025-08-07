package com.jtrade.registration_service.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.jtrade.registration_service.model.Client;

public interface ClientRepository extends JpaRepository<Client, Long> {
}
