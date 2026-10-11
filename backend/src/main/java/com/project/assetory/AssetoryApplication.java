package com.project.assetory;

import com.project.assetory.connect.codef.CodefTimeouts;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AssetoryApplication {

	public static void main(String[] args) {
		CodefTimeouts.apply(); // HttpURLConnection 첫 사용 전에 설정돼야 적용됨
		SpringApplication.run(AssetoryApplication.class, args);
	}

}
