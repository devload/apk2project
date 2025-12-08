plugins {
    id 'com.android.application' version '${agpVersion}'
<#if hasKotlin>
    id 'org.jetbrains.kotlin.android' version '${kotlinVersion}'
</#if>
<#if hasHilt>
    id 'com.google.dagger.hilt.android' version '2.50'
</#if>
<#if hasFirebase>
    id 'com.google.gms.google-services' version '4.4.0'
</#if>
<#if hasCrashlytics>
    id 'com.google.firebase.crashlytics' version '2.9.9'
</#if>
}

android {
    namespace '${namespace}'
    compileSdk ${compileSdk}

    defaultConfig {
        applicationId "${applicationId}"
        minSdk ${minSdk}
        targetSdk ${targetSdk}
        versionCode ${versionCode}
        versionName "${versionName}"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
<#if hasVectorDrawables>
        vectorDrawables {
            useSupportLibrary true
        }
</#if>
<#if multiDexEnabled>
        multiDexEnabled true
</#if>
    }

    buildTypes {
        release {
            minifyEnabled ${minifyEnabled?c}
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            minifyEnabled false
            debuggable true
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_${javaVersion}
        targetCompatibility JavaVersion.VERSION_${javaVersion}
    }

<#if hasKotlin>
    kotlinOptions {
        jvmTarget = '${javaVersion}'
    }

</#if>
<#if buildFeatures?has_content>
    buildFeatures {
<#list buildFeatures as feature, enabled>
        ${feature} = ${enabled?c}
</#list>
    }

</#if>
<#if hasCompose>
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.4'
    }

</#if>
    packaging {
        resources {
            excludes += '/META-INF/{AL2.0,LGPL2.1}'
            excludes += '/META-INF/DEPENDENCIES'
        }
    }

    lint {
        abortOnError false
        checkReleaseBuilds false
    }
}

dependencies {
    // Core Android libraries
<#list coreDependencies as dep>
    implementation '${dep}'
</#list>

<#if detectedDependencies?has_content>
    // Detected third-party libraries
<#list detectedDependencies as dep>
    implementation '${dep.notation}'<#if dep.confidence lt 0.9> // Confidence: ${(dep.confidence * 100)?string["0"]}%</#if>
</#list>
</#if>

<#if multiDexEnabled>
    // MultiDex support
    implementation 'androidx.multidex:multidex:2.0.1'
</#if>

    // Testing dependencies
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
