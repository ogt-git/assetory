package com.project.assetory;

import com.project.assetory.connect.codef.CodefTimeouts;
import com.project.assetory.global.config.AppTimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AssetoryApplication {

	public static void main(String[] args) {
		AppTimeZone.apply(); // 서버 OS 시간대(UTC 등)와 상관없이 JVM 기본 시간대를 Asia/Seoul로
		CodefTimeouts.apply(); // HttpURLConnection 첫 사용 전에 설정돼야 적용됨
		SpringApplication.run(AssetoryApplication.class, args);
	}

}
