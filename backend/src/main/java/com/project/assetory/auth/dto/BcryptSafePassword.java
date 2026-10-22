package com.project.assetory.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 비밀번호 UTF-8 바이트 길이 ≤ 72 — 해싱 전에 400으로 거절 (Bcrypt는 72바이트 초과 입력에 예외를 던진다)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptSafePassword.Validator.class)
public @interface BcryptSafePassword {

    String message() default PasswordPolicy.BYTES_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptSafePassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return PasswordPolicy.withinBcryptLimit(value);
        }
    }
}
