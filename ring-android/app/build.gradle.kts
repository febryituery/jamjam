val kotlin_version: String by rootProject.extra
val hilt_version: String by rootProject.extra
val dokka_version: String by rootProject.extra
val archs: CharSequence by project
val buildFirebase = project.hasProperty("buildFirebase") || gradle.startParameter.taskRequests.toString().contains("Firebase")

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("dagger.hilt.android.plugin")
}

android {
    compileSdk = 31
    buildToolsVersion = "31.0.0"
    ndkVersion = "23.0.7599858"
    defaultConfig {
        minSdk = 21
        targetSdk = 31
        versionCode = 322
        versionName = "20211104-01"
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir( "src/main/libs")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")

            packagingOptions{
                doNotStrip("*/armeabi/*.so")
                doNotStrip("*/armeabi-v7a/*.so")
                doNotStrip("*/arm64-v8a/*.so")
                doNotStrip("*/x86/*.so")
                doNotStrip("*/x86_64/*.so")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
    }
    flavorDimensions += "push"
    productFlavors {
        create("noPush") {
            dimension = "push"
        }
        create("withFirebase") {
            dimension = "push"
        }
    }
    signingConfigs {
        create("config") {
            keyAlias = "ring"
            storeFile = file("../keystore.bin")
        }
    }
    lint {
        disable("MissingTranslation")
    }
    splits {
        abi {
            isEnable = true
            reset()
            val sp = archs.split(",")
            include("armeabi-v7a, arm64-v8a, x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation (project(":libjamiclient"))
    implementation ("org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version")
    implementation ("androidx.core:core-ktx:1.7.0")
    implementation ("androidx.appcompat:appcompat:1.4.0")
    implementation ("androidx.constraintlayout:constraintlayout:2.1.2")
    implementation ("androidx.legacy:legacy-support-core-utils:1.0.0")
    implementation ("androidx.cardview:cardview:1.0.0")
    implementation ("androidx.preference:preference-ktx:1.1.1")
    implementation( "androidx.recyclerview:recyclerview:1.2.1")
    implementation( "androidx.leanback:leanback:1.2.0-alpha02")
    implementation ("androidx.leanback:leanback-preference:1.2.0-alpha02")
    implementation ("androidx.tvprovider:tvprovider:1.1.0-alpha01")
    implementation ("androidx.media:media:1.4.3")
    implementation ("androidx.percentlayout:percentlayout:1.0.0")
    implementation ("com.google.android.material:material:1.5.0-beta01")
    implementation ("com.google.android.flexbox:flexbox:3.0.0")
    implementation ("org.osmdroid:osmdroid-android:6.1.11")
    implementation ("androidx.sharetarget:sharetarget:1.2.0-rc01")

    // ORM
    implementation ("com.j256.ormlite:ormlite-android:5.6")

    // Barcode scanning
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation ("com.google.zxing:core:3.3.3")

    // Dagger dependency injection
    implementation("com.google.dagger:hilt-android:$hilt_version")
    kapt("com.google.dagger:hilt-android-compiler:$hilt_version")

    // Glide
    implementation ("com.github.bumptech.glide:glide:4.12.0")
    kapt ("com.github.bumptech.glide:compiler:4.12.0")

    // RxAndroid
    implementation ("io.reactivex.rxjava3:rxandroid:3.0.0")
    implementation ("io.reactivex.rxjava3:rxjava:3.1.2")

    implementation ("com.jsibbold:zoomage:1.3.1")
    implementation ("com.googlecode.ez-vcard:ez-vcard:0.11.3")

    "withFirebaseImplementation"("com.google.firebase:firebase-messaging:23.0.0") {
        exclude(group= "com.google.firebase", module= "firebase-core")
        exclude(group= "com.google.firebase", module= "firebase-analytics")
        exclude(group= "com.google.firebase", module= "firebase-measurement-connector")
    }
}

kapt {
    correctErrorTypes = true
}

if (buildFirebase) {
    println ("apply plugin $buildFirebase")
    apply(plugin = "com.google.gms.google-services")
}