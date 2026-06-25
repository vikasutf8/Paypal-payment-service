package com.payflow.globalConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@EnableConfigServer
@SpringBootApplication
public class GlobalConfigApplication {

	public static void main(String[] args) {
		SpringApplication.run(GlobalConfigApplication.class, args);
	}

}
