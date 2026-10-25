package com.sis.sharding;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Quiesces HTTP requests during migration on this application instance. */
@Component @Order(0)
public class TopologyFilter extends OncePerRequestFilter {
 private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true);
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String path=req.getRequestURI();
  boolean migration=path.equals("/api/shards/admin/add") || path.equals("/api/shards/admin/remove");
  var selected=migration?lock.writeLock():lock.readLock();
  selected.lock();try{chain.doFilter(req,res);}finally{selected.unlock();}
 }
}
