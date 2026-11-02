# Guide : installer Flick sur tes téléphones

Ce guide part de zéro. Il te mène de l'archive `flick-workspace.zip` jusqu'à un transfert entre deux téléphones. Suis les parties dans l'ordre.

## 0. Ce qu'il te faut

- Un ordinateur sous Windows 10 ou 11, Linux ou macOS, avec environ 25 Go libres.
- Deux téléphones Android 10 ou plus récent. L'onde NameDrop complète demande Android 13. Avant, tu vois un simple anneau.
- Un câble USB qui transfère des données, pas seulement la charge. Un seul câble suffit.
- Une connexion Internet pour les téléchargements (plusieurs Go).
- Du temps. Compte une à deux heures la première fois. La compilation de Rust prend à elle seule 20 à 40 minutes (estimation).

Les commandes sont données pour PowerShell sous Windows, et pour un terminal sous Linux et macOS. Quand elles diffèrent, les deux versions sont écrites.

## Version courte

Quand tout est installé, voici les commandes dans l'ordre. Chaque partie suivante les explique.

```bash
unzip -o flick-workspace.zip
cd flick
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o android/app/src/main/jniLibs build -p flick-ffi --release
cd android
./gradlew installDebug
```

Sous Windows, remplace la dernière commande par `.\gradlew.bat installDebug`.

## 1. Installer les outils (une seule fois)

### 1.1 Android Studio

1. Télécharge Android Studio sur https://developer.android.com/studio et installe-le.
2. Au premier lancement, laisse l'assistant installer le SDK Android avec les réglages par défaut.
3. Ouvre le SDK Manager : écran d'accueil, puis More Actions, puis SDK Manager. Dans un projet ouvert, c'est le menu Tools, puis SDK Manager.
4. Onglet SDK Platforms : coche Android 15 (API 35).
5. Onglet SDK Tools : coche Android SDK Build-Tools, Android SDK Platform-Tools et NDK (Side by side). Pour le NDK, choisis la version 27. Clique sur Apply et attends la fin.
6. Note le chemin du SDK, affiché en haut du SDK Manager. Par défaut :
   - Windows : `C:\Users\TON_NOM\AppData\Local\Android\Sdk`
   - Linux : `~/Android/Sdk`
   - macOS : `~/Library/Android/sdk`

### 1.2 Rust

**Windows.**
1. Télécharge `rustup-init.exe` sur https://rustup.rs.
2. Lance-le. Il te propose d'installer les Visual Studio Build Tools avec le composant « Desktop development with C++ ». Accepte. Sans lui, Rust ne peut pas compiler sous Windows.
3. Choisis l'installation par défaut (option 1).

**Linux et macOS.**

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

Choisis l'option 1. Sous Linux, installe aussi les outils de compilation : `sudo apt install build-essential` (Ubuntu et Debian).

Ferme le terminal, ouvres-en un nouveau, puis vérifie :

```bash
rustc --version
cargo --version
```

Les deux commandes doivent afficher un numéro de version.

### 1.3 Variables d'environnement

Deux choses doivent être connues de ton terminal : l'emplacement du NDK et celui de `adb` (l'outil qui parle à ton téléphone).

Cherche d'abord le nom exact du dossier du NDK :

```powershell
# Windows
dir "$env:LOCALAPPDATA\Android\Sdk\ndk"
```

```bash
# Linux
ls ~/Android/Sdk/ndk
# macOS
ls ~/Library/Android/sdk/ndk
```

Tu vois un dossier du genre `27.0.12077973`. Remplace `NUMERO` ci-dessous par ce nom.

**Windows (PowerShell) :**

```powershell
setx ANDROID_NDK_HOME "$env:LOCALAPPDATA\Android\Sdk\ndk\NUMERO"
setx PATH "$env:PATH;$env:LOCALAPPDATA\Android\Sdk\platform-tools"
```

**Linux :** ajoute ces lignes à la fin de `~/.bashrc`.

```bash
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/NUMERO"
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
```

**macOS :** même chose dans `~/.zshrc`, avec le chemin `$HOME/Library/Android/sdk`.

**Ferme le terminal et ouvres-en un nouveau.** Sans cela, les variables ne sont pas prises en compte. Vérifie :

```bash
adb --version
```

### 1.4 cargo-ndk

Cet outil compile Rust pour Android.

```bash
cargo install cargo-ndk --locked
```

La compilation de l'outil dure quelques minutes.

## 2. Récupérer le projet

Place `flick-workspace.zip` dans un dossier simple, par exemple `Documents`. Ouvre un terminal dans ce dossier et lance :

```bash
unzip -o flick-workspace.zip
cd flick
```

Sous Windows, fais un clic droit sur le fichier et choisis « Extraire tout ». Sous Linux, installe `unzip` si besoin avec `sudo apt install unzip`. Tu obtiens un dossier `flick`. **Tous les chemins de ce guide partent de ce dossier**, sauf indication contraire.

### Appliquer une nouvelle archive sur un projet existant

Si tu as déjà le projet et que tu reçois une nouvelle archive, **ne copie pas les fichiers un par un**. Un fichier oublié mélange l'ancien et le nouveau code, et la compilation échoue avec des erreurs comme « no method named set_trusted ». Fais ceci, depuis le dossier où se trouve l'archive :

```bash
rm -rf flick
unzip -o flick-workspace.zip
cp -a flick/. ~/AndroidStudioProjects/Flick_r/
cd ~/AndroidStudioProjects/Flick_r
rm -rf android/app/src/main/java/com/flick/app/data/acoustic
rm -f android/app/src/main/java/com/flick/app/ui/effects/NameDropShader.kt android/app/src/main/java/com/flick/app/ui/effects/NameDropWave.kt
rm -rf crates/flick-acoustic/tests third_party/namedrop
```

Adapte `~/AndroidStudioProjects/Flick_r` au vrai nom de ton dossier. Les trois dernières lignes suppriment d'anciens fichiers que la copie ne supprime pas.

Vérifie que la copie est complète. Chaque commande doit afficher 1 ou plus :

```bash
grep -c "pub fn set_trusted" crates/flick-core/src/node.rs
grep -c "fn tilts" android/app/src/main/java/com/flick/app/data/SensorFlickSource.kt
grep -c "AIRDROP_AGSL" android/app/src/main/java/com/flick/app/ui/effects/AirDropShader.kt
```

Ensuite, refais la compilation de la partie 4.2 **depuis ce même dossier racine, jamais depuis le dossier `android`**. La commande contient un chemin relatif : lancée depuis `android`, elle écrit la bibliothèque dans `android/android/...`, où Gradle ne la trouve pas.

## 3. Vérifier Rust sur ton ordinateur (conseillé)

```bash
cargo test
```

La première fois, rustup télécharge tout seul la version de Rust demandée par le projet (1.97.1), puis Cargo compile les dépendances. Compte 10 à 30 minutes. Tu dois voir à la fin des lignes `test result: ok` et aucun `FAILED`. Environ 35 tests passent.

Si cette étape échoue, ne va pas plus loin et envoie-moi l'erreur.

## 4. Fabriquer la bibliothèque Rust pour Android

C'est l'étape la plus longue. Elle transforme le cœur Rust en un fichier `libflick_ffi.so` que l'app Android charge.

### 4.1 Ajouter les cibles Android

Lance cette commande **dans le dossier `flick`**, pour qu'elle s'applique à la version de Rust du projet :

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

### 4.2 Compiler

Cette commande marche dans tous les terminaux :

```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o android/app/src/main/jniLibs build -p flick-ffi --release
```

Sous Linux, macOS ou Git Bash, le script fait la même chose : `scripts/build-android-libs.sh`.

Pour aller plus vite, compile seulement pour les téléphones modernes. Presque tous les téléphones depuis 2017 sont en arm64 :

```bash
cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build -p flick-ffi --release
```

Si ton téléphone est très ancien (32 bits), ajoute `-t armeabi-v7a`.

### 4.3 Vérifier le résultat

```bash
ls android/app/src/main/jniLibs/arm64-v8a
```

Sous Windows : `dir android\app\src\main\jniLibs\arm64-v8a`. Tu dois voir le fichier `libflick_ffi.so`.

**Je n'ai jamais lancé cette étape.** Je n'avais pas le NDK Android là où j'ai écrit le code. Si elle échoue, c'est ici que se trouve la plus grande chance d'erreur. Envoie-moi alors les 40 dernières lignes affichées.

## 5. Préparer le téléphone 1

### 5.1 Activer le mode développeur

1. Ouvre les Réglages, puis « À propos du téléphone ».
2. Touche **7 fois** de suite la ligne « Numéro de build ». Un message annonce que tu es développeur.
   - Samsung : Informations sur le logiciel, puis Numéro de build.
   - Xiaomi : la ligne s'appelle « Version MIUI » ou « Version HyperOS ».
   - Tecno, Infinix, Itel : Réglages, « Mon téléphone » ou « À propos », puis Version, puis Numéro de build.
3. Reviens aux Réglages et ouvre « Options pour les développeurs ». Elles se trouvent parfois dans Système ou Réglages supplémentaires.
4. Active **Débogage USB**.

### 5.2 Brancher et autoriser

1. Branche le téléphone à l'ordinateur avec le câble.
2. Sur le téléphone, choisis le mode USB « Transfert de fichiers » si une notification le propose.
3. Une fenêtre demande « Autoriser le débogage USB ? ». Coche « Toujours autoriser » et touche Autoriser.
4. Vérifie sur l'ordinateur :

```bash
adb devices
```

Tu dois voir une ligne avec un numéro de série suivi de `device`. Si tu lis `unauthorized`, retourne sur le téléphone et accepte la fenêtre. Si rien n'apparaît, lis la partie 10.

## 6. Installer l'app sur le téléphone 1

### Avec la ligne de commande

```bash
cd android
./gradlew installDebug
```

Sous Windows : `.\gradlew.bat installDebug`.

La première fois, Gradle télécharge lui-même sa propre version, puis toutes les bibliothèques Android (plusieurs centaines de Mo). Compte 5 à 15 minutes. À la fin, tu lis `BUILD SUCCESSFUL`, et l'app **Flick** apparaît sur le téléphone.

### Avec Android Studio

1. Ouvre Android Studio, puis Open, et choisis le dossier **`flick/android`**, pas `flick`.
2. Attends la fin de la synchronisation (barre de progression en bas).
3. En haut, choisis ton téléphone dans la liste des appareils.
4. Appuie sur le triangle vert (Run).

Ouvre l'app. Accepte les demandes d'autorisation : appareils à proximité pour le Bluetooth et le Wi-Fi (ou localisation sur les anciens Android) et notifications. Il n'y a plus de micro.

## 7. Installer sur le deuxième téléphone

### Méthode la plus simple : les deux branchés en même temps

1. Active le mode développeur et le débogage USB sur le deuxième téléphone (partie 5.1).
2. Branche les deux téléphones à l'ordinateur, avec deux ports USB ou un hub.
3. Vérifie que `adb devices` affiche deux lignes `device`.
4. Lance :

```bash
cd android
./gradlew installDebug
```

Gradle installe l'app sur **tous** les téléphones branchés.

### Un seul câble

Installe sur le premier téléphone (partie 6). Débranche-le, branche le deuxième, autorise le débogage USB (partie 5.2), puis relance `./gradlew installDebug`. Cette fois, c'est rapide : rien n'est recompilé.

### Choisir un téléphone précis quand les deux sont branchés

```bash
adb devices
adb -s NUMERO_DE_SERIE install -r app/build/outputs/apk/debug/app-debug.apk
```

Remplace `NUMERO_DE_SERIE` par la valeur de la première colonne. Lance cette commande depuis le dossier `android`.

### Sans câble : le débogage sans fil (Android 11 et plus)

Le téléphone et l'ordinateur doivent être sur le même Wi-Fi.

1. Dans les Options pour les développeurs, active « Débogage sans fil ».
2. Touche « Associer l'appareil avec un code d'association ». Le téléphone affiche une adresse, un port et un code.
3. Sur l'ordinateur :

```bash
adb pair ADRESSE:PORT
```

Saisis le code quand il est demandé.
4. Reviens à l'écran « Débogage sans fil ». Il affiche une autre adresse et un autre port. Lance :

```bash
adb connect ADRESSE:PORT
```

5. Vérifie avec `adb devices`, puis lance `./gradlew installDebug`.

### Sans câble ni Wi-Fi : envoyer le fichier de l'app

Après la partie 6, le fichier de l'app se trouve ici :

```
flick/android/app/build/outputs/apk/debug/app-debug.apk
```

1. Envoie ce fichier au deuxième téléphone (clé USB, Bluetooth, ou WhatsApp en document).
2. Ouvre le fichier sur le téléphone.
3. Android demande d'autoriser l'installation d'applications inconnues pour l'application que tu utilises. Autorise.
4. Si Play Protect avertit, touche « Installer quand même ».

## 8. Premier test

### Ce qui doit être activé sur les deux téléphones

- Bluetooth allumé.
- Sur Android 11 et plus ancien, la **localisation** aussi : Android l'exige pour chercher en Bluetooth.
- Toutes les autorisations acceptées au premier lancement : appareils à proximité (Bluetooth et Wi-Fi) ou localisation, et notifications.
- Flick ouvert à l'écran sur les deux. L'app cherche seulement quand elle est à l'écran.

### Le déroulé

1. Ouvre Flick sur les deux téléphones. Ils se cherchent tout de suite par Bluetooth, sans que tu touches à rien.
2. Sur le téléphone qui envoie, choisis ton fichier (Send File).
3. Rapproche les téléphones à quelques centimètres, puis **incline les deux en même temps**, comme pour AirDrop entre deux iPhone. Il faut un vrai basculement d'environ 30 degrés, en moins d'une seconde. Un téléphone qui bouge un peu, qui marche ou qui vibre ne compte pas.
4. Chaque téléphone annonce son geste à l'autre par Bluetooth. Le plus proche de ceux qui ont fait le geste au même moment est choisi.
5. Quand la liaison est réellement établie, l'animation AirDrop se joue **sur les deux téléphones** : l'arrière-plan se floute, une onde traverse l'écran et le fichier ressort net au milieu.
6. Fais ensuite glisser la carte du fichier vers le haut, ou fais le geste Flick. L'animation repart et le fichier part.
7. Sur l'autre téléphone, une carte tombe du haut et se remplit pendant la réception. Touche Open pour ouvrir le fichier.

### Le temps visé : moins de 3 secondes

Le Bluetooth trouve l'autre téléphone en moins d'une seconde. La liaison Wi-Fi est plus lente. Flick la prépare **avant le geste**, dès que l'autre téléphone est à moins d'un mètre environ, donc pendant que tu choisis ton fichier. Au moment du geste elle est déjà prête.

La carte Diagnostics affiche « Last time to be ready » : le temps réel entre le premier signal de l'autre téléphone et sa liaison prête. **Note-le et envoie-le-moi.** C'est la vraie mesure qui dira si les 3 secondes sont tenues sur tes téléphones.

### Même Wi-Fi ou pas

- **Même Wi-Fi :** aucune liaison à former, la préparation est très rapide.
- **Sans Wi-Fi partagé :** les téléphones forment un groupe Wi-Fi Direct automatiquement. Le Wi-Fi doit être allumé mais pas forcément connecté. Si le Wi-Fi est éteint, un bandeau propose de l'allumer d'un toucher, car Android interdit à une application de l'allumer seule.

### Partager avec plusieurs téléphones

1. Installe Flick sur trois téléphones ou plus.
2. Par défaut, seul le téléphone le plus proche reçoit, et seul le plus proche a le droit d'envoyer chez toi.
3. Active l'interrupteur « Share with several phones » dans l'onglet Nearby, sur le téléphone qui envoie **et** sur ceux qui reçoivent.
4. Fais glisser la carte vers le haut : l'écran de transfert liste chaque destinataire avec son avancement.

### Un gros fichier

Envoie une vidéo d'environ 500 Mo. Pendant l'envoi, quitte l'app ou éteins l'écran. Une notification affiche la progression et le transfert continue.

## 9. Voir ce qui se passe : les journaux

Quand quelque chose ne marche pas, le téléphone écrit ce qui s'est passé. Branche-le et lance :

```bash
# Linux, macOS, Git Bash
adb logcat | grep -i flick
# Windows PowerShell
adb logcat | Select-String -Pattern "flick"
```

Pour voir seulement les plantages :

```bash
adb logcat *:E
```

Avec deux téléphones branchés, ajoute `-s NUMERO_DE_SERIE` après `adb`.

Arrête l'affichage avec Ctrl et C.

## 9 bis. La carte Diagnostics

Dans l'onglet Nearby, touche « Diagnostics ». Elle montre ce que fait le Bluetooth :

- **Bluetooth** : « ready » quand tout va bien. Sinon : « off », « permission missing » ou un code d'erreur.
- **Advertising** et **Scanning** : doivent être « yes » tous les deux. Le premier annonce ton téléphone, le second cherche les autres.
- **Frames heard from other phones** : doit augmenter quand l'autre téléphone est ouvert et proche. Un zéro veut dire que ce téléphone n'entend personne.
- **Signal of the closest phone** : la force du signal en dBm. Autour de moins 40 à moins 55 à quelques centimètres, moins 70 et plus bas à plusieurs mètres.
- **Gestures detected here** : augmente chaque fois que ton geste d'inclinaison est reconnu.
- **Last time to be ready** : le temps mesuré jusqu'à la liaison prête.

Quand deux téléphones ne se voient pas ou ne s'appairent pas, ouvre cette carte sur les deux. Une photo des deux cartes suffit pour trouver l'étape qui bloque.

## 10. Problèmes fréquents

| Ce que tu vois | Cause probable | Que faire |
| --- | --- | --- |
| `adb` n'est pas reconnu | `platform-tools` n'est pas dans le PATH | Refais la partie 1.3 et ouvre un nouveau terminal |
| `adb devices` n'affiche rien | Câble de charge seule, mauvais mode USB, pilote manquant | Change de câble et de port, choisis « Transfert de fichiers », sous Windows installe le pilote USB du fabricant |
| `unauthorized` | Tu n'as pas accepté la fenêtre sur le téléphone | Débranche, rebranche, accepte. Au besoin, dans les Options pour les développeurs, touche « Révoquer les autorisations de débogage USB » |
| `linker link.exe not found` (Windows) | Les Build Tools C++ manquent | Installe Visual Studio Build Tools avec « Desktop development with C++ », puis rouvre le terminal |
| cargo-ndk dit qu'il ne trouve pas le NDK | `ANDROID_NDK_HOME` absent ou faux | Refais la partie 1.3, vérifie le nom du dossier, ouvre un nouveau terminal |
| `SDK location not found` | Gradle ne connaît pas le SDK | Crée le fichier `android/local.properties` avec la ligne `sdk.dir=C\:\\Users\\TON_NOM\\AppData\\Local\\Android\\Sdk` (adapte le chemin). Android Studio le crée tout seul |
| `UnsatisfiedLinkError` ou `Unable to load library 'flick_ffi'` | La partie 4 n'a pas été faite, ou pas pour ce processeur | Refais la partie 4, vérifie que `jniLibs` contient le fichier, puis lance `./gradlew clean installDebug` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Une autre version signée autrement est installée | `adb uninstall com.flick.app`, puis réinstalle |
| Play Protect bloque l'installation | Application inconnue | Touche « Installer quand même » |
| L'app se ferme juste après les autorisations | La bibliothèque Rust est absente, ou plus ancienne que les fichiers Kotlin | Refais la partie 4.2, puis dans Android Studio Build, Clean Project, puis Run. Désormais l'app affiche l'erreur dans un bandeau rouge au lieu de se fermer : photographie-le |
| Erreur de compilation Kotlin pendant `installDebug` | Le code Kotlin n'a jamais été compilé avant toi | Copie le message complet et envoie-le-moi |
| L'app se ferme au démarrage | Plantage | Lance `adb logcat *:E` et ouvre l'app, puis envoie-moi les lignes rouges |
| Les téléphones ne se voient pas | Bluetooth éteint, autorisation refusée, localisation éteinte (Android 11 et moins), Flick fermé sur l'autre | Ouvre Diagnostics. Regarde Bluetooth, Advertising, Scanning et Frames heard. Reprends la liste de la partie 8 |
| Le geste n'est pas reconnu | Mouvement trop faible, trop lent ou fait en marchant | Fais un vrai basculement d'environ 30 degrés en moins d'une seconde, téléphone immobile avant. Gestures detected here doit augmenter |
| « Several phones are close » | Deux téléphones à la même distance | Éloigne les autres téléphones et recommence le geste |
| Pas d'effet de flou sur Android 12 | L'onde complète demande Android 13 | Android 12 affiche seulement le flou. Avant, aucun effet |
| Le téléphone reste sur « Connecting » | Pas de Wi-Fi partagé, et le Wi-Fi Direct échoue | Teste d'abord le test A pour isoler le problème, puis lis le message sous le nom |

## 11. Règle d'or : toujours reconstruire les deux ensemble

Le fichier Kotlin `flick_ffi.kt` et la bibliothèque `libflick_ffi.so` doivent venir **de la même version du code Rust**. Si tu reçois une nouvelle archive, refais toujours la partie 4.2 avant de relancer l'app. Sinon l'app se ferme dès le premier appel à Rust, avec le message « API checksum mismatch ».

## 12. Après chaque modification du code

| Tu as changé | Commandes |
| --- | --- |
| Seulement du Kotlin ou des écrans | `cd android` puis `./gradlew installDebug` |
| Du Rust | Partie 4.2 pour recompiler, puis `./gradlew installDebug` |
| L'interface entre Rust et Kotlin (le crate `flick-ffi`) | Régénère le Kotlin (voir ci-dessous), puis partie 4.2 et `installDebug` |

Pour régénérer le code Kotlin, depuis le dossier `flick` :

```bash
scripts/generate-kotlin.sh
```

Sous Windows, sans bash :

```powershell
cargo build -p flick-ffi --features cli
cargo run -p flick-ffi --features cli --bin uniffi-bindgen -- generate --library target\debug\flick_ffi.dll --language kotlin --out-dir android\app\src\main\java
```

Sous macOS, le fichier de la bibliothèque s'appelle `libflick_ffi.dylib`, et sous Linux `libflick_ffi.so`, dans `target/debug`.

## 13. Si ça échoue : ce qu'il faut m'envoyer

Envoie-moi ces quatre choses, dans le même message :

1. Le numéro de la partie où ça s'arrête, par exemple « partie 4.2 ».
2. Les 30 à 50 dernières lignes affichées par le terminal, copiées en texte, pas en capture d'écran.
3. Ton système (Windows, Linux ou macOS) et le modèle de tes téléphones avec leur version d'Android.
4. Le résultat de la commande `adb devices`.

Avec ces éléments, je peux corriger sans te poser de questions.

## À savoir sur le Bluetooth

- Chaque téléphone diffuse un identifiant qui ne change pas : le début de l'empreinte de son certificat. Une personne qui scanne à proximité peut donc reconnaître le même téléphone d'un jour à l'autre. Flick diffuse seulement quand l'app est à l'écran. Un identifiant qui change régulièrement est une amélioration prévue.
- Le Bluetooth sert à trouver l'autre téléphone et à estimer sa distance. Il ne transporte aucun fichier.
- Android interdit à une application d'allumer le Wi-Fi sans toi depuis la version 10. Flick détecte que le Wi-Fi est éteint et t'amène en un toucher au panneau qui l'allume. Il fait de même pour le Bluetooth.
- La distance vient de la force du signal. Elle est lissée par un filtre mais reste une estimation.

## 14. Compiler sans rien installer : la CI sur GitHub

Le projet contient une chaîne de vérification automatique (`.github/workflows/ci.yml`). Elle compile l'app avec le vrai SDK Android sur les serveurs de GitHub et te donne l'APK à télécharger. Tu n'as alors plus besoin d'installer le NDK ni cargo-ndk sur ton ordinateur, et les erreurs de compilation apparaissent sur GitHub avant d'arriver chez toi.

**Mise en place, une seule fois :**

1. Crée un dépôt vide sur https://github.com/new (privé si tu veux).
2. Dans le dossier racine du projet :

```bash
git init -b main
git add .
git commit -m "Flick"
git remote add origin https://github.com/TON_NOM/flick.git
git push -u origin main
```

3. Ouvre l'onglet **Actions** du dépôt. La chaîne démarre toute seule. La première fois, compte 30 à 50 minutes. Les suivantes sont plus courtes grâce au cache.

**Ce qu'elle fait, dans l'ordre :**

| Étape | Ce qu'elle vérifie |
| --- | --- |
| Rust | Le code est bien formaté, l'analyseur clippy ne signale rien, tous les tests passent, le shader AirDrop calcule comme l'original, aucun tiret long |
| Bindings | Le fichier Kotlin généré correspond exactement au Rust. C'est ce qui t'a fait tomber sur l'erreur « checksum mismatch » |
| Android | Compile la bibliothèque Rust pour les trois processeurs, lance les tests unitaires et compile l'app. L'APK est ensuite téléchargeable |
| Émulateur | Lance l'app sur un émulateur Android 14. Vérifie qu'elle s'ouvre et reste ouverte, et que la bibliothèque Rust se charge et correspond au Kotlin |

**Récupérer l'APK :** ouvre l'exécution terminée dans Actions, descends jusqu'à « Artifacts » et télécharge `flick-debug-apk`. C'est un zip qui contient `app-debug.apk`. Installe-le comme dans la partie 7.

**Une version datée :** `git tag v0.1.0` puis `git push --tags` publie l'APK dans l'onglet Releases du dépôt.

**Ce qu'elle ne peut pas faire :** tester le vrai Bluetooth, le vrai gyroscope ou un transfert entre deux téléphones. Il faut pour cela de vrais appareils. L'émulateur n'a pas de Bluetooth.

**Quand une étape échoue,** la CI copie les lignes utiles dans une annotation sur la page de résumé de l'exécution, lisible sans connexion, y compris par moi si tu me donnes le lien. Le journal complet, lui, demande d'être connecté.

**Si Git suit des fichiers qu'il ne devrait pas** (par exemple le dossier `target`, des dizaines de Mo de compilation), la règle `.gitignore` ne suffit pas : elle n'agit que sur les fichiers pas encore suivis. Retire-les du suivi sans les supprimer du disque :

```bash
git rm -r -q --cached target
git commit -m "Stop tracking build output"
```

**Les deux dernières étapes ne bloquent pas encore** (Android lint et l'émulateur) : elles n'ont jamais tourné, donc elles peuvent signaler des problèmes de configuration qui n'en sont pas. Quand elles passent, enlève la ligne `continue-on-error` correspondante dans le fichier pour qu'elles bloquent.

## Ce que ce projet ne fait pas encore

- Il n'existe pas d'application pour ordinateur. Seuls les téléphones Android sont pris en charge.
- Le partage par lien, pour qu'une personne éloignée télécharge un fichier sans passer par un cloud, n'est pas encore fait.
- Un transfert interrompu recommence à zéro.
- La distance affichée est estimée d'après la force du signal Bluetooth. Elle est lissée mais reste une estimation.
- Le Bluetooth, le geste d'inclinaison et l'effet AirDrop n'ont jamais tourné sur un vrai téléphone avant toi. Les premiers essais révéleront des erreurs.
- L'autorisation de l'envoi par descripteur de fichier est testée sur ordinateur seulement.
