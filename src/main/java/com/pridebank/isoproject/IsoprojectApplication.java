package com.pridebank.isoproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;


@SpringBootApplication
@EnableFeignClients
public class IsoprojectApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsoprojectApplication.class, args);
    }

}
