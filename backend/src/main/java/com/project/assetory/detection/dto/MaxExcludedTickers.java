package com.project.assetory.detection.dto;

import com.project.assetory.detection.config.DetectionProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * 제외 종목 수 ≤ assetory.detection.max-excluded-tickers — 상한이 설정값이라 @Size 대신 쓴다.
 * 검증기는 Spring이 만들어 설정을 주입한다(@Valid 경로 → 400 VALIDATION_ERROR). null은 통과(제외 없음)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxExcludedTickers.Validator.class)
public @interface MaxExcludedTickers {

    String message() default "제외할 수 있는 종목 수를 넘었습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxExcludedTickers, List<?>> {

        private final DetectionProperties properties;

        public Validator(DetectionProperties properties) {
            this.properties = properties;
        }

        @Override
        public boolean isValid(List<?> value, ConstraintValidatorContext context) {
            return value == null || value.size() <= properties.maxExcludedTickers();
        }
    }
}
