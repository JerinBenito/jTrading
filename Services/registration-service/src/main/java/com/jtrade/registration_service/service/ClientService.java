package com.jtrade.registration_service.service;

import java.util.List;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.jtrade.registration_service.model.Client;
import com.jtrade.registration_service.repository.ClientRepository;

@Service
public class ClientService {

    private final ClientRepository clientRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = new BCryptPasswordEncoder(); // initialize encoder
    }

    public Client registerClient(Client client) {
        // hash the password before saving
        String rawPassword = client.getClientUserPassword();
        String encodedPassword = passwordEncoder.encode(rawPassword);
        client.setClientUserPassword(encodedPassword);

        return clientRepository.save(client);
    }

    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }
}
