package com.praksa.service;

import com.praksa.dto.auth.AuthResponse;
import com.praksa.dto.auth.LoginRequest;
import com.praksa.dto.auth.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
}
