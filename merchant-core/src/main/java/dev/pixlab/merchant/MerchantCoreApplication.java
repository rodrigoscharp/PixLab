package dev.pixlab.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MerchantCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantCoreApplication.class, args);
    }
}
