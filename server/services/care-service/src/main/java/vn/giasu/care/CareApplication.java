package vn.giasu.care;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class CareApplication {
    public static void main(String[] args) { SpringApplication.run(CareApplication.class, args); }
}
