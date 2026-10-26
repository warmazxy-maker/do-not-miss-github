package com.donotmiss.backend.auth;

import com.donotmiss.backend.access.RoleRepository;

/** Exposes AuthService's package-private role check to tests in other packages. */
public final class AuthServiceAccess {
    private AuthServiceAccess() {
    }

    public static String registrableRole(RoleRepository roleRepository, String requested) {
        return new AuthService(null, roleRepository, null).registrableRole(requested);
    }
}
