package com.andrewCorp.webWallet.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ComponentScan({ "com.andrewCorp.webWallet.task" })
public class SpringTaskConfig {

}
