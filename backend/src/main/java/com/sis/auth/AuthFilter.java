package com.sis.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sis.schema.Student;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.annotation.Order;

@Component @Order(1)
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth; private final RateLimiter limiter; private final ObjectMapper json;
    public AuthFilter(AuthService auth,RateLimiter limiter,ObjectMapper json) { this.auth=auth;this.limiter=limiter;this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();
        boolean protectedPath=path.startsWith("/api/") || path.startsWith("/actuator/");
        if(!protectedPath || path.equals("/actuator/health") || req.getMethod().equals("OPTIONS")) { chain.doFilter(req,res);return; }
        boolean publicAuth=path.equals("/api/auth/login") || path.equals("/api/auth/register");
        Student actor;
        try {
            String header=req.getHeader("Authorization");
            String token=header!=null && header.startsWith("Bearer ")?header.substring(7):null;
            actor=publicAuth?null:auth.authenticate(token);
            var limit=limiter.allow(publicAuth?req.getRemoteAddr():(actor==null?req.getRemoteAddr():actor.id()),publicAuth);
            if(!limit.allowed()) {
                long wait=limit.retryAfterSeconds();
                res.setHeader("Retry-After",Long.toString(wait)); res.setHeader("X-RateLimit-Remaining","0");
                error(res,429,"Too many requests; retry in "+wait+" seconds");return;
            }
            res.setHeader("X-RateLimit-Remaining",Long.toString(limit.remaining()));
            if(!publicAuth && actor==null) { error(res,401,"Sign in required");return; }
            req.setAttribute("sis.actor",actor);req.setAttribute("sis.token",token);
        } catch(RuntimeException e) { error(res,503,"Authentication infrastructure unavailable");return; }
        if(!publicAuth) {
            boolean admin=(path.equals("/api/listings") && req.getMethod().equals("DELETE")) || path.startsWith("/api/shards") || path.startsWith("/api/kv") || path.startsWith("/api/schema") || path.startsWith("/api/baseline") || path.startsWith("/actuator/")
                || path.equals("/api/academic/seed") || path.equals("/api/academic/search/rebuild") || path.startsWith("/api/academic/metrics/");
            boolean staff=path.startsWith("/api/crawl") || path.startsWith("/api/urls") || (path.startsWith("/api/notifications") && req.getMethod().equals("POST") && !path.endsWith("/read-all"));
            if((admin && !"ADMIN".equals(actor.role())) || (staff && "STUDENT".equals(actor.role()))) { error(res,403,"Insufficient permissions");return; }
        }
        chain.doFilter(req,res);
    }
    private void error(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status);res.setContentType("application/json");json.writeValue(res.getOutputStream(),Map.of("status",status,"message",message));
    }
}
