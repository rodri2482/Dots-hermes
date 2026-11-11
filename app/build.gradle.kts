plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Firebase (notifications push, statistiques d'usage) : appliqué seulement si app/google-services.json est présent,
    // pour que le dépôt se construise tel quel (CI, contributeurs) — le push et les statistiques sont alors désactivés.
    alias(libs.plugins.google.services) apply false
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

/** Variable d'environnement (CI) ou propriété Gradle (-P / gradle.properties), sinon [default]. */
fun setting(env: String, property: String, default: String): String =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
        ?: default

// Le mode release ne doit jamais emprunter silencieusement l'identité de debug.
val releaseSigningNames = listOf("SIGNING_KEYSTORE", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")
val releaseSigningReady = releaseSigningNames.all { !System.getenv(it).isNullOrBlank() }
    && System.getenv("SIGNING_KEYSTORE")?.let { file(it).isFile } == true

android {
    namespace = "app.dotshermes"
    compileSdk = 36

    defaultConfig {
        // Identité de Claydot (fork de Dots-hermes) : le paquet Android diffère du namespace Kotlin, gardé
        // à app.dotshermes pour rester fusionnable avec le projet d'origine.
        applicationId = "app.claydot"
        minSdk = 26
        targetSdk = 36
        // La CI fournit un numéro croissant et un nom de version suffixé par le numéro de build.
        versionCode = setting("VERSION_CODE", "appVersionCode", "2").toInt()
        versionName = setting("VERSION_NAME", "appVersionName", "0.2.0")
        // Adresse proposée sur l'écran d'appairage ; vide par défaut (chacun saisit la sienne).
        buildConfigField("String", "DEFAULT_GATEWAY_URL", "\"${setting("DEFAULT_GATEWAY_URL", "defaultGatewayUrl", "")}\"")
        // Dépôt GitHub dont les releases mettent l'app à jour (« owner/repo ») ; vide = pas de mise à jour
        // intégrée. La CI y met le dépôt qui publie : un fork ne propose donc que ses propres releases.
        buildConfigField("String", "UPDATE_REPO", "\"${setting("UPDATE_REPO", "updateRepo", "rodri2482/Dots-hermes")}\"")
    }

    signingConfigs {
        // Clé de publication fournie par la CI (secrets) ou en local via les mêmes variables.
        val keystore = System.getenv("SIGNING_KEYSTORE")
        if (releaseSigningReady && keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            val offline = providers.gradleProperty("directApiMock").orNull == "true"
            buildConfigField("boolean", "DIRECT_API_MOCK", offline.toString())
            if (offline) {
                applicationIdSuffix = ".offline"
                versionNameSuffix = "-offline-fixture"
            }
        }
        release {
            // R8 : code réduit et optimisé, ressources inutilisées retirées (règles : proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            // Vérification locale d'une release réduite à côté de l'app installée (-PreleaseIdSuffix=.r8check).
            providers.gradleProperty("releaseIdSuffix").orNull?.let { applicationIdSuffix = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sans signature propriétaire, la vérification ci-dessous bloque tout APK/AAB release.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Refuse une publication sans signature propriétaire ; ne révèle aucun secret."
    doLast {
        if (!releaseSigningReady) throw GradleException(
            "Release signing is not configured. Provide all SIGNING_* variables and an existing keystore. Use assembleDebug for private tests."
        )
    }
}
// Garder tests et compilation release accessibles sans clé ; protéger tous les emballages APK/AAB.
tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle", "validateSigningRelease") }
    .configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.process)
    // Firebase tire une vieille version de fragment (< 1.3) ; celle-ci gère correctement les demandes de permission.
    implementation(libs.androidx.fragment)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // Statistiques d'usage (Google Analytics) : inactives, comme le push, sans google-services.json.
    implementation(libs.firebase.analytics)
    // Appairage de l'app web : lecture du QR code du navigateur (modèle ML Kit embarqué, sans Play Services).
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    // Gmail en lecture seule : écran d'accord de Google et jeton d'accès (API d'autorisation d'Identity).
    implementation(libs.play.services.auth)
    // Réponses en Markdown : titres, listes, tableaux, et coloration du code (mikepenz, Apache-2.0).
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    // Photos envoyées aux modèles de vision : remises droites selon l'EXIF avant compression.
    implementation(libs.androidx.exifinterface)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Même API JSON que sur Android ; serveur HTTP local pour tester sans clé ni appel payant.
    testImplementation("org.json:json:20260814")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
