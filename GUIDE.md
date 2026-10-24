# Guide : installer Flick sur tes téléphones

Ce guide part de zéro. Il te mène de l'archive `flick-workspace.tar.gz` jusqu'à un transfert entre deux téléphones. Suis les parties dans l'ordre.

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
tar -xzf flick-workspace.tar.gz
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

Place `flick-workspace.tar.gz` dans un dossier simple, par exemple `Documents`. Ouvre un terminal dans ce dossier et lance :

```bash
tar -xzf flick-workspace.tar.gz
cd flick
```

La commande `tar` existe sous Windows 10 et 11. Tu obtiens un dossier `flick`. **Tous les chemins de ce guide partent de ce dossier**, sauf indication contraire.

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

Ouvre l'app. Accepte les demandes d'autorisation : micro, appareils à proximité (ou localisation sur les anciens Android) et notifications.

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

### Avant de commencer, sur les deux téléphones

- Ouvre Flick et accepte toutes les autorisations.
- Mets le **volume média** entre 70 et 100 pour que le signal ultrason soit émis assez fort.
- Retire les coques épaisses qui couvrent le micro ou le haut-parleur.
- Reste dans une pièce calme. Un bruit fort perturbe l'écoute.
- Désactive l'économiseur de batterie.
- Dans l'onglet Nearby, vérifie que l'interrupteur est activé.

### Test A : même Wi-Fi

C'est le cas le plus simple. Commence par lui.

1. Connecte les deux téléphones au même Wi-Fi. Ils peuvent aussi se connecter au partage de connexion de l'un d'eux.
2. Ouvre Flick sur les deux.
3. Pose les téléphones à 20 à 50 cm l'un de l'autre.
4. Attends jusqu'à une dizaine de secondes. Chaque téléphone émet son signal toutes les 2 à 4 secondes, et chaque signal dure 1,8 seconde.
5. Dans l'onglet Nearby, l'autre téléphone apparaît sur le radar avec un cercle de chargement. Quand il est vérifié, une pastille verte avec une coche apparaît sur son avatar.
6. Sur le téléphone 1, touche Send File dans l'accueil et choisis un fichier. L'écran Nearby s'ouvre avec la carte du fichier. Quand l'autre téléphone est prêt, la carte prend un contour et l'onde NameDrop traverse l'écran.
7. Fais glisser la carte vers le haut, ou fais le geste Flick avec le téléphone. L'onde repart et le fichier part.
8. Sur le téléphone 2, une carte tombe du haut. L'icône se remplit pendant la réception. Touche Open pour ouvrir le fichier.

### Test B : sans Wi-Fi partagé, en Wi-Fi Direct

1. Déconnecte les deux téléphones de tout réseau, mais laisse le Wi-Fi activé.
2. Désactive tout partage de connexion.
3. Ouvre Flick sur les deux, à 20 à 50 cm.
4. Les téléphones se détectent par le son, puis forment un groupe Wi-Fi Direct. Ça peut prendre jusqu'à 25 secondes. Sous le nom de l'autre téléphone, tu lis « Connecting ».
5. Quand la pastille verte apparaît, envoie un fichier comme dans le test A.

Si le message d'erreur s'affiche sous le nom du téléphone, note-le et envoie-le-moi. C'est la partie la moins sûre du projet, parce que les téléphones Android se comportent différemment ici.

### Test C : un gros fichier

1. Envoie une vidéo d'environ 500 Mo.
2. Pendant l'envoi, quitte l'app ou éteins l'écran.
3. Une notification « Sending to ... » affiche la progression. Le transfert continue.

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
| Erreur de compilation Kotlin pendant `installDebug` | Le code Kotlin n'a jamais été compilé avant toi | Copie le message complet et envoie-le-moi |
| L'app se ferme au démarrage | Plantage | Lance `adb logcat *:E` et ouvre l'app, puis envoie-moi les lignes rouges |
| Les téléphones ne se voient pas | Volume trop bas, trop loin, micro non autorisé, bruit | Reprends la liste de la partie 8, rapproche-les à 20 cm, monte le volume |
| Toujours rien, malgré tout cela | Le haut-parleur ou le micro d'un téléphone ne gère pas 18 kHz | Certains téléphones d'entrée de gamme filtrent ces fréquences. Dis-le-moi : je peux ajouter un mode avec un signal audible |
| Le téléphone reste sur « Connecting » | Pas de Wi-Fi partagé, et le Wi-Fi Direct échoue | Teste d'abord le test A pour isoler le problème, puis lis le message sous le nom |
| Pas d'onde sur Android 12 ou moins | Normal | L'onde NameDrop demande Android 13. Avant, un simple anneau apparaît |

## 11. Après chaque modification du code

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

## 12. Si ça échoue : ce qu'il faut m'envoyer

Envoie-moi ces quatre choses, dans le même message :

1. Le numéro de la partie où ça s'arrête, par exemple « partie 4.2 ».
2. Les 30 à 50 dernières lignes affichées par le terminal, copiées en texte, pas en capture d'écran.
3. Ton système (Windows, Linux ou macOS) et le modèle de tes téléphones avec leur version d'Android.
4. Le résultat de la commande `adb devices`.

Avec ces éléments, je peux corriger sans te poser de questions.

## Ce que ce projet ne fait pas encore

- Il n'existe pas d'application pour ordinateur. Seuls les téléphones Android sont pris en charge.
- Un transfert interrompu recommence à zéro.
- La distance affichée est estimée d'après le volume du son. Elle n'est pas mesurée.
- Le code Kotlin et le Wi-Fi Direct n'ont jamais tourné sur un vrai téléphone avant toi. Les premiers essais révéleront des erreurs, et c'est normal.
