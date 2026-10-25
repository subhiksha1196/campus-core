package com.sis.auth;

import com.sis.schema.Student;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }
    public record Register(@NotBlank @Size(max=100) String name,@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=60) String department,@Min(1) @Max(8) int year,@NotBlank @Size(min=10,max=128) String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=128) String password) {}
    public record Profile(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=60) String department,@Min(0) @Max(8) int year) {}
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@Valid @RequestBody Register r) {
        return service.profile(service.register(r.name(),r.email(),r.department(),r.year(),r.password()));
    }
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody Login r) { return service.login(r.email(),r.password()); }
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest r) { return service.profile((Student)r.getAttribute("sis.actor")); }
    @PutMapping("/me") public Map<String,Object> update(HttpServletRequest req,@Valid @RequestBody Profile r) {
        return service.profile(service.update((Student)req.getAttribute("sis.actor"),r.name(),r.department(),r.year()));
    }
    @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletRequest r) {
        service.logout((String)r.getAttribute("sis.token")); return Map.of("ok",true);
    }
}
