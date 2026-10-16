package com.example.demo.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希。
 *
 * <p>原来密码是明文存库、明文等值查询（{@code findByStuidAndPassword}），一次数据库
 * 泄漏就等于全站密码泄漏；而且等值查询让攻击者可以用一次请求试一次密码，没有任何
 * 计算成本挡着。</p>
 *
 * <p>BCrypt 自带随机盐与计算成本因子，同一个密码每次哈希结果都不同，暴力破解成本
 * 远高于 SHA-256 这类快哈希。</p>
 */
@Configuration
public class Passwords {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
