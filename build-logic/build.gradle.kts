plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("i18nVerify") {
            id = "com.aliothmoon.maameow.i18n-verify"
            implementationClass = "com.aliothmoon.maameow.buildlogic.I18nVerifyPlugin"
        }
    }
}
