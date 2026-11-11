# Règles R8/ProGuard de la release (TASKS.md, tâche 7c).
#
# Parti pris : règles conservatrices pour une première passe — OkHttp, Firebase et ML Kit
# embarquent déjà leurs propres règles consumer, celles ci-dessous garantissent le fonctionnement
# sans risque, quitte à réduire moins. À relâcher plus tard, une vérification sur téléphone à chaque
# assouplissement (crashs « ClassNotFoundException » ou réponses vides = règle manquante).

# OkHttp (WebSocket de la gateway, app updater, réponse rapide) : détection de la plateforme HTTP
# par introspection à l'exécution.
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Firebase (messaging + analytics) : initialisé par réflexion quand google-services.json est présent.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ML Kit (lecture du QR d'appairage web, modèle embarqué) : JNI natif (libbarhopper) + paquets
# internes gms.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# org.json : fourni par la plate-forme Android, pas une dépendance — keep sans effet, présent parce
# que la liste des tâches l'exige explicitement.
-keep class org.json.** { *; }

# Traçabilité des plantages : les lignes ci-dessous gardent des piles d'appels réobfusquables avec
# app/build/outputs/mapping/release/mapping.txt (retrace).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Connexion Google (Gmail en lecture seule) : Play Services Auth fournit ses propres règles consumer ;
# l'API Gmail est appelée en HTTP brut (JSON), rien d'autre à garder.

# WorkManager (vérification des mises à jour en arrière-plan) : sa base Room est créée par réflexion
# (WorkDatabase_Impl.<init>). Sans cette règle, la release plante au lancement (constaté sur émulateur).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }

# Modèle hors ligne : LiteRT-LM appelle ses classes depuis le code natif (JNI) et par réflexion.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
