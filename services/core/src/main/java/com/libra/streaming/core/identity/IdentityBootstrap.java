package com.libra.streaming.core.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class IdentityBootstrap implements ApplicationRunner {
    private final IdentityAccountService accounts;
    public IdentityBootstrap(IdentityAccountService accounts) { this.accounts = accounts; }

    @Override
    public void run(ApplicationArguments args) { accounts.bootstrapAdministrator(); }
}
