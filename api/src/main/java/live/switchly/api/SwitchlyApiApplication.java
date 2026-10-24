package live.switchly.api;

import live.switchly.api.model.Organization;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.UUID;

@SpringBootApplication
public class SwitchlyApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SwitchlyApiApplication.class, args);
	}

}
