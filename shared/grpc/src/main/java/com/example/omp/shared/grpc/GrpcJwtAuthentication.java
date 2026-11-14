package com.example.omp.shared.grpc;

import java.util.Collection;
import java.util.LinkedHashSet;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Preserve OAuth scopes and verified Keycloak realm roles for method authorization. */
public final class GrpcJwtAuthentication {
    private GrpcJwtAuthentication() {}

    public static JwtAuthenticationConverter converter() {
        var converter = new JwtAuthenticationConverter();
        var scopes = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    var authorities = new LinkedHashSet<GrantedAuthority>(scopes.convert(jwt));
                    Object realm = jwt.getClaims().get("realm_access");
                    if (realm instanceof java.util.Map<?, ?> claims
                            && claims.get("roles") instanceof Collection<?> roles) {
                        for (Object value : roles) {
                            if (value instanceof String role
                                    && role.matches("[A-Za-z0-9_.:-]{1,64}"))
                                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                        }
                    }
                    return authorities;
                });
        return converter;
    }
}
