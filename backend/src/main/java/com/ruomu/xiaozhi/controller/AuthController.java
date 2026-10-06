package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.security.AccountService;
import com.ruomu.xiaozhi.security.AccountUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts;
    private final AuthenticationManager manager;
    private final SecurityContextRepository repository;
    private final SessionAuthenticationStrategy sessions;
    private final Map<String, Window> attempts = new ConcurrentHashMap<>();
    public AuthController(AccountService accounts, AuthenticationManager manager, SecurityContextRepository repository, SessionAuthenticationStrategy sessions) {
        this.accounts = accounts; this.manager = manager; this.repository = repository; this.sessions = sessions;
    }
    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "token", token.getToken()); }
    @GetMapping("/me") public AccountUser.View me(Authentication authentication) { return AccountUser.require(authentication).view(); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AccountUser.View register(@RequestBody Credentials value, HttpServletRequest request) {
        limit(request.getRemoteAddr());
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号密码");
        return accounts.register(value.username(), value.password());
    }
    @PostMapping("/login")
    public AccountUser.View login(@RequestBody Credentials value, HttpServletRequest request, HttpServletResponse response) {
        limit(request.getRemoteAddr());
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号密码");
        if (!AccountService.normalize(value.username()).matches("[a-z0-9_]{3,32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名格式不正确");
        AccountService.validatePassword(value.password());
        try {
            Authentication auth = manager.authenticate(new UsernamePasswordAuthenticationToken(AccountService.normalize(value.username()), value.password()));
            sessions.onAuthentication(auth, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            repository.saveContext(context, request, response);
            return AccountUser.require(auth).view();
        } catch (AuthenticationException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误"); }
    }
    private synchronized void limit(String address) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        if (attempts.size() > 10000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过多，请稍后再试");
        Window window = attempts.computeIfAbsent(address, ignored -> new Window(now + 300000));
        if (++window.count > 20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录或注册请求过多，请5分钟后再试");
    }
    private static class Window { int count; final long expiresAt; Window(long expiresAt) { this.expiresAt = expiresAt; } }
    public record Credentials(String username, String password) {}
}
