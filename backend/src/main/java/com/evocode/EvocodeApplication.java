package com.evocode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.evocode.mapper")
public class EvocodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvocodeApplication.class, args);
    }
}
