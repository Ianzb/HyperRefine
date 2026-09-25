plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.ianzb.hyperrefine.hook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    api(libs.libxposed.service)
    api(libs.dexkit)
}
