package com.smartcoffee.identity;

import org.springframework.boot.SpringApplication;



public class TestIdentityServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(TestIdentityServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

