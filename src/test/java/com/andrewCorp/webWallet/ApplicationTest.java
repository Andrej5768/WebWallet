package com.andrewCorp.webWallet;

import com.andrewCorp.webWallet.persistence.dao.PrivilegeRepository;
import com.andrewCorp.webWallet.persistence.dao.RoleRepository;
import com.andrewCorp.webWallet.persistence.dao.UserRepository;
import com.andrewCorp.webWallet.persistence.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PrivilegeRepository privilegeRepository;

    @Test
    void setupDataLoaderCreatesRolesPrivilegesAndAdmin() {
        assertThat(privilegeRepository.findByName("READ_PRIVILEGE")).isNotNull();
        assertThat(privilegeRepository.findByName("WRITE_PRIVILEGE")).isNotNull();
        assertThat(privilegeRepository.findByName("CHANGE_PASSWORD_PRIVILEGE")).isNotNull();
        assertThat(roleRepository.findByName("ROLE_USER").getPrivileges()).hasSize(2);
        assertThat(roleRepository.findByName("ROLE_ADMIN").getPrivileges()).hasSize(3);

        User admin = userRepository.findByEmail("test@test.com");
        assertThat(admin).isNotNull();
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getRoles()).extracting("name").containsExactly("ROLE_ADMIN");
    }
}
