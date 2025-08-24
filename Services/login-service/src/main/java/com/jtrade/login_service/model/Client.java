package com.jtrade.login_service.model;


import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "client_master_table")
@Data @Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long clientId;

    private String clientName;
    private String clientEmailId;
    private String clientPhno;
    private String clientAddress;
    private String clientUserName;
    private String clientUserPassword; 
//     public String getClientUserName() { return clientUserName; }
// public String getClientUserPassword() { return clientUserPassword; }
 
}
