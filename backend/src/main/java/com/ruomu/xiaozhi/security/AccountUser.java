package com.ruomu.xiaozhi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public record AccountUser(String userId, String username, String passwordHash) implements UserDetails, Serializable {
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_USER")); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
    public static AccountUser require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AccountUser user))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }
    public View view() { return new View(userId, username); }
    public record View(String userId, String username) {}
}
