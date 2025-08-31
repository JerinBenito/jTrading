package com.jtrade.dashboard_service.controller;

import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @GetMapping("/public/status")
    public String publicStatus() {
        return "Dashboard Service is running (public)!";
    }

    @GetMapping("/secure/info")
    public String secureInfo(Authentication authentication) {
        return "Dashboard accessible. Logged in as: " ;
    }
}

