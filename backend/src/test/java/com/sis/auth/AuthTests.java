package com.sis.auth;

import com.sis.schema.Student;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthTests {
    @Test void passwordsAreSaltedAndVerify() {
        String a=Passwords.hash("correct-password"),b=Passwords.hash("correct-password");
        assertNotEquals(a,b);assertTrue(Passwords.matches("correct-password",a));assertFalse(Passwords.matches("wrong-password",a));
    }
    @Test void malformedAndSeedPasswordsFailClosed() {
        for(String s:new String[]{"!seeded","",null,"0$x$x","99999999$x$x"}) assertFalse(Passwords.matches("x",s));
    }
    @Test void emailCanonicalization() { assertEquals("student@example.com",AuthService.email(" Student@Example.COM ")); }
    private MockHttpServletResponse request(String path,String method,Student student,boolean allowed,String fakeId) throws Exception {
        AuthService auth=mock(AuthService.class);RateLimiter limit=mock(RateLimiter.class);
        when(auth.authenticate(any())).thenReturn(student);when(limit.allow(anyString(),anyBoolean())).thenReturn(allowed
            ? new TokenBucketRateLimiter.Decision(true,10,0)
            : new TokenBucketRateLimiter.Decision(false,0,60_000));
        MockHttpServletRequest req=new MockHttpServletRequest(method,path);
        req.addHeader("Authorization","Bearer token");if(fakeId!=null)req.addHeader("X-User-Id",fakeId);
        MockHttpServletResponse res=new MockHttpServletResponse();
        new AuthFilter(auth,limit,new ObjectMapper()).doFilter(req,res,(r,s)->s.getWriter().write("accepted"));
        return res;
    }
    private Student student() { return new Student("S1","Student","s@e.com","CSE",1,"hash","STUDENT",Instant.now()); }
    @Test void spoofedHeaderIsNotAuthentication() throws Exception { assertEquals(401,request("/api/academic/courses","GET",null,true,"admin").getStatus()); }
    @Test void studentCannotAdministerShards() throws Exception { assertEquals(403,request("/api/shards/admin/add","POST",student(),true,null).getStatus()); }
    @Test void studentCannotSendNotifications() throws Exception { assertEquals(403,request("/api/notifications","POST",student(),true,null).getStatus()); }
    @Test void studentCannotDeleteListings() throws Exception { assertEquals(403,request("/api/listings","DELETE",student(),true,null).getStatus()); }
    @Test void authenticatedCourseReadAllowed() throws Exception { assertEquals("accepted",request("/api/academic/courses","GET",student(),true,null).getContentAsString()); }
    @Test void rateLimitReturnsRetryAfter() throws Exception { var r=request("/api/auth/login","POST",null,false,null);assertEquals(429,r.getStatus());assertEquals("60",r.getHeader("Retry-After")); }
}
