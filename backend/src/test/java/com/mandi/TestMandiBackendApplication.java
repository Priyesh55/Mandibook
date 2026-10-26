package com.mandi;

import org.springframework.boot.SpringApplication;

public class TestMandiBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(MandiBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
