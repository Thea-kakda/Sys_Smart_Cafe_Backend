package com.smartcoffee.config;
import java.util.Locale; 
import org.springframework.context.annotation.*; 
import org.springframework.web.servlet.*; 
// import org.springframework.web.servlet.config.annotation.InterceptorRegistry; 
import org.springframework.web.servlet.i18n.*;
@Configuration public class LocalizationConfig { @Bean LocaleResolver localeResolver(){var r=new AcceptHeaderLocaleResolver();r.setDefaultLocale(Locale.US);r.setSupportedLocales(java.util.List.of(Locale.US,Locale.forLanguageTag("km-KH")));return r;} }

