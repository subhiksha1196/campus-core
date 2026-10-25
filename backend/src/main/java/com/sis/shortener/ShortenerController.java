package com.sis.shortener;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.sis.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShortenerController {
    private final ShortenerService service;
    private final ShortUrlRepository repo;

    public ShortenerController(ShortenerService service, ShortUrlRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    public record CreateRequest(@NotBlank String url, String alias) {
    }

    @PostMapping("/api/urls")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        ShortUrl s = service.create(req.url(), req.alias());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", s.code());
        body.put("shortUrl", service.shortLink(s.code()));
        body.put("url", s.url());
        if (req.alias() != null && !req.alias().isBlank() && !req.alias().equals(s.code())) {
            body.put("aliasTaken", true);
            body.put("message", "Alias '" + req.alias() + "' is already used, so '" + s.code() + "' was assigned instead");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/api/urls")
    public List<Map<String, Object>> recent() {
        return repo.findAll(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent().stream().map(this::view).toList();
    }

    @GetMapping("/api/urls/{code}/stats")
    public Map<String, Object> stats(@PathVariable String code) {
        ShortUrl s = repo.findById(code).orElseThrow(() -> new NotFoundException("Unknown code: " + code));
        return view(s);
    }

    /** Proposed design: Redis-cached redirect. */
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return toRedirect(service.resolveCached(code));
    }

    /** Baseline for load tests: MongoDB-only redirect. */
    @GetMapping("/baseline/s/{code}")
    public ResponseEntity<Void> redirectBaseline(@PathVariable String code) {
        return toRedirect(service.resolveBaseline(code));
    }

    private ResponseEntity<Void> toRedirect(Optional<String> url) {
        return url.<ResponseEntity<Void>>map(u -> ResponseEntity.status(HttpStatus.FOUND).location(URI.create(u)).build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> view(ShortUrl s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.code());
        m.put("shortUrl", service.shortLink(s.code()));
        m.put("url", s.url());
        m.put("createdAt", s.createdAt());
        m.put("clicks", service.clicks(s.code()));
        return m;
    }
}
