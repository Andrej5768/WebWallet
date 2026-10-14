package com.andrewCorp.webWallet;

import com.andrewCorp.webWallet.persistence.model.Privilege;
import com.andrewCorp.webWallet.persistence.model.Role;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.service.IUserService;
import com.andrewCorp.webWallet.web.dto.UserDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Base class for tests running against the full application context with an in-memory H2 database.
 * Each test runs in a transaction that is rolled back afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "password123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected IUserService userService;

    protected User registerUser() {
        return registerUser("user-" + UUID.randomUUID() + "@test.com");
    }

    protected User registerUser(String email) {
        UserDto dto = new UserDto();
        dto.setFirstName("First");
        dto.setLastName("Last");
        dto.setEmail(email);
        dto.setPassword(PASSWORD);
        dto.setMatchingPassword(PASSWORD);
        return userService.registerNewUserAccount(dto);
    }

    /**
     * Authenticates the request the same way CustomAuthenticationProvider does:
     * the principal is the persistence User, which the controllers rely on.
     */
    protected static RequestPostProcessor as(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Role role : user.getRoles()) {
            authorities.add(new SimpleGrantedAuthority(role.getName()));
            for (Privilege privilege : role.getPrivileges()) {
                authorities.add(new SimpleGrantedAuthority(privilege.getName()));
            }
        }
        return authentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
