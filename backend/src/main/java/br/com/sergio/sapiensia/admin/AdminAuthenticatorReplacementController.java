package br.com.sergio.sapiensia.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/authenticator-replacement")
class AdminAuthenticatorReplacementController {

    @PostMapping
    ResponseEntity<?> start() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/confirmation")
    ResponseEntity<?> confirm() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
