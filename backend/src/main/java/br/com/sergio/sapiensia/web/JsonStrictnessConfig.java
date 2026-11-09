package br.com.sergio.sapiensia.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Text fields accept only JSON strings. The {@code spring.jackson.mapper.allow-coercion-of-scalars} property does not
 * cover this case: Jackson turns a number or boolean into a String unless the coercion for textual targets is set to
 * fail.
 */
@Configuration(proxyBeanMethods = false)
class JsonStrictnessConfig {

    @Bean
    JsonMapperBuilderCustomizer refuseNumbersAndBooleansAsText() {
        return builder -> builder.withCoercionConfig(
                LogicalType.Textual,
                config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
