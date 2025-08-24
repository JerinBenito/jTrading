package com.jtrade.login_service.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Service;

import com.jtrade.login_service.model.Client;   // ✅ Correct Client class
import com.jtrade.login_service.repository.ClientRepository;

@Service
public class MyUserDetailsService implements UserDetailsService {

    @Autowired
    private ClientRepository clientRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Client client = clientRepository.findByClientUserName(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return User.builder()
                .username(client.getClientUserName())
                .password(client.getClientUserPassword())
                .roles("USER")  // Later you can fetch roles from DB
                .build();
    }
}
