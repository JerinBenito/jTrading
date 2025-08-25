package com.jtrade.login_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.*;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import com.jtrade.login_service.security.JwtTokenUtil;
import com.jtrade.login_service.security.MyUserDetailsService;

import java.util.HashMap;
import java.util.Map;

@RestController
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private MyUserDetailsService userDetailsService;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    // DTO class for login request
    public static class LoginRequest {
        private String username;
        private String password;

        // getters & setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest loginRequest) {
        try {
            // Authenticate user
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    loginRequest.getUsername(),
                    loginRequest.getPassword()
                )
            );

            // Load user details
            final UserDetails userDetails = userDetailsService.loadUserByUsername(loginRequest.getUsername());

            // Generate JWT token
            final String token = jwtTokenUtil.generateToken(userDetails.getUsername());

            // Return response
            Map<String, String> response = new HashMap<>();
            response.put("token", token);
            return response;

        } catch (BadCredentialsException e) {
            throw new RuntimeException("Invalid username or password", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "Welcome to Dashboard! You are authenticated.";
    }
}
