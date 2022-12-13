package com.andrewCorp.webWallet.security;

public interface ISecurityUserService {

    String validatePasswordResetToken(String token);

}
