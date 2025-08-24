package com.jtrade.login_service.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.jtrade.login_service.model.Client;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {
    Optional<Client> findByClientUserName(String username);
}
