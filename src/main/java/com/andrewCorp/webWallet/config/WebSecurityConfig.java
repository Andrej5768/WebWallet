package com.andrewCorp.webWallet.config;

import com.andrewCorp.webWallet.persistence.dao.UserRepository;
import com.andrewCorp.webWallet.security.CustomAuthenticationProvider;
import com.andrewCorp.webWallet.security.CustomRememberMeServices;
import com.andrewCorp.webWallet.security.CustomWebAuthenticationDetailsSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.InMemoryTokenRepositoryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@ComponentScan(basePackages = {"com.andrewCorp.webWallet.security"})
// @ImportResource({ "classpath:webSecurityConfig.xml" })
@EnableWebSecurity
public class WebSecurityConfig {

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private AuthenticationSuccessHandler myAuthenticationSuccessHandler;

    @Autowired
    private LogoutSuccessHandler myLogoutSuccessHandler;

    @Autowired
    private AuthenticationFailureHandler authenticationFailureHandler;

    @Autowired
    private CustomWebAuthenticationDetailsSource authenticationDetailsSource;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Bean
    public AuthenticationManager authenticationManager() {
        final ProviderManager authenticationManager = new ProviderManager(authProvider());
        // Required for AuthenticationFailureListener/AuthenticationSuccessEventListener (login attempt blocking)
        authenticationManager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(applicationEventPublisher));
        return authenticationManager;
    }

    @Bean
    public SecurityFilterChain filterChain(final HttpSecurity http) throws Exception {
        // @formatter:off
        http
                .authenticationManager(authenticationManager())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/","/index*", "/login*", "/logout*", "/signin/**", "/signup/**",
                                "/user/registration*", "/registrationConfirm*", "/expiredAccount*", "/registration*",
                                "/badUser*", "/user/resendRegistrationToken*" ,"/forgetPassword*", "/user/resetPassword*","/user/savePassword*","/updatePassword*",
                                "/user/changePassword*", "/emailError*", "/resources/**","/old/user/registration*","/successRegister*",
                                "/user/enableNewLoc*", "/img/**").permitAll()
                        .requestMatchers("/invalidSession*").anonymous()
                        .requestMatchers("/user/updatePassword*").hasAuthority("CHANGE_PASSWORD_PRIVILEGE")
                        .anyRequest().hasAuthority("READ_PRIVILEGE"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/homepage.html")
                        .failureUrl("/login?error=true")
                        .successHandler(myAuthenticationSuccessHandler)
                        .failureHandler(authenticationFailureHandler)
                        .authenticationDetailsSource(authenticationDetailsSource)
                        .permitAll())
                .sessionManagement(session -> session
                        .invalidSessionUrl("/invalidSession.html")
                        .sessionFixation(fixation -> fixation.none())
                        .maximumSessions(1).sessionRegistry(sessionRegistry()))
                .logout(logout -> logout
                        .logoutSuccessHandler(myLogoutSuccessHandler)
                        .invalidateHttpSession(true)
                        .logoutSuccessUrl("/logout.html?logSucc=true")
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .rememberMe(rememberMe -> rememberMe.rememberMeServices(rememberMeServices()).key("theKey"));
        // @formatter:on
        return http.build();

//        httpSecurity
//                .csrf()
//                .disable()
//                .authorizeRequests()
//                //Доступ только для не зарегистрированных пользователей
//                .antMatchers("/registration", "/static/**").not().fullyAuthenticated()
//                //Доступ только для пользователей с ролью Администратор
//                .antMatchers("/admin/**").hasRole("ADMIN")
//                .antMatchers("/wallet").hasRole("USER")
//                //Доступ разрешен всем пользователей
//                .antMatchers("/", "/403").permitAll()
//                //Все остальные страницы требуют аутентификации
//                .anyRequest().authenticated()
//                .and()
//                //Настройка для входа в систему
//                .formLogin()
//                .loginPage("/login")
//                //Перенарпавление на главную страницу после успешного входа
//                .defaultSuccessUrl("/wallet")
//                .permitAll()
//                .and()
//                .logout()
//                .permitAll()
//                .logoutSuccessUrl("/");
    }

// beans

    @Bean
    public DaoAuthenticationProvider authProvider() {
        final CustomAuthenticationProvider authProvider = new CustomAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(encoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder encoder() {
        return new BCryptPasswordEncoder(11);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public RememberMeServices rememberMeServices() {
        CustomRememberMeServices rememberMeServices = new CustomRememberMeServices("theKey", userDetailsService, new InMemoryTokenRepositoryImpl());
        return rememberMeServices;
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        String hierarchy = "ROLE_ADMIN > ROLE_STAFF \n ROLE_STAFF > ROLE_USER";
        return RoleHierarchyImpl.fromHierarchy(hierarchy);
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

}
