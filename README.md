# User Switch

English · [Français](#français)

Switch user profile with a sequence on the hardware buttons, without root. Built for GrapheneOS, also works on a Pixel running stock Android (see [Tested compatibility](#tested-compatibility)).

## How it works

- **Daemon** (`src/dev/userswitch/daemon/Main.java`): started with ADB shell rights through `app_process`, it survives ADB going away. It reads the buttons from `/dev/input` (power, volume up, volume down), cuts the presses into sequences (`UP UP DOWN:long`, chords `UP+DOWN`), sends every sequence worth 3 points or more to the app over binder, then switches profile if the app returns a target.
- **App** (`MainActivity`, `ConfigProvider`, `Store`): records sequences and maps each one to a profile or to "Next profile". The daemon does the cutting, so a recorded sequence is always cut the same way as the one you repeat later.

Languages: English (default) and French (`res/values-fr/`). The app follows the system language, and can be set on its own in Settings › Apps › User Switch › Language (Android 13+, through `res/xml/locales_config.xml`). To add a language: a `res/values-<code>/strings.xml` and one line in `locales_config.xml`.

Sequence score: 1 point per press, +1 for a long press (≥ 500 ms), +1 for several buttons pressed together. A sequence ends after 800 ms without a press.

Only full profiles are offered as targets: a work profile or stock Android's Private space (`FLAG_PROFILE`) cannot be opened with `switchUser` and is not listed.

## Interface

Material 3 without a library: the project builds without Gradle, so no Material Components. `M3.java` draws the shapes, pressed states, type scale and components (cards, buttons, chips) with the system's own views.

- **Material You colors**, in three tiers, light and dark:
  - Android 14+ (`values-v34`): the system's Material 3 roles (`system_surface_container_*`, `system_primary_*`…);
  - Android 12–13 (`values-v31`): the wallpaper palettes (`system_accent*`, `system_neutral*`), tones picked by hand;
  - Android 11 (`values`): the Material 3 baseline palette (purple), as there are no dynamic colors.
- **Icon**: adaptive, in the wallpaper colors (Android 12+), with a monochrome layer for themed icons.
- **Screen**: status card (daemon, Shizuku), recording banner with a countdown, sequences as a grouped list with the keys as chips, a collapsible "Without Shizuku" card with a Copy button, and a floating "Record" button. Content runs under the system bars, with a background behind the status bar and a scrim behind 3-button navigation.
- **Shizuku guide** in 6 steps, with a button per step (Play Store, About phone, developer options, Shizuku, permission). It tracks progress and folds away as soon as the daemon runs. Once Shizuku is paired, a stopped Shizuku points straight at the "Start Shizuku" step.
- **Foldables and tablets**: one column below 600 dp wide, two panes above (status and guide on the left, sequences on the right). Folding or unfolding relays the screen out without recreating the activity (`configChanges`). The Shizuku pairing tip adapts: Fold open, split screen; Fold closed, type the code in Shizuku's notification.

## Hardening

`Hardening.java`. Everywhere, the app:

- asks for memory tagging (MTE) in sync mode (`android:memtagMode="sync"`), applied where the hardware and system turn it on;
- hides overlay windows above it (`setHideOverlayWindows`);
- ignores touches that go through another window (`setFilterTouchesWhenObscured`), since it grants Shizuku permission.

On GrapheneOS, a "GrapheneOS hardening" card lists the per-app settings only the user can turn on (memory tagging, compatibility mode off, dynamic code loading restricted, Sensors permission denied) and opens the app's page in Settings. GrapheneOS is recognised by its system apps (`app.grapheneos.apps`, `app.grapheneos.info`): its reproducible builds carry a generic user and host, useless for detection.

## With Shizuku (no computer)

The app starts the daemon as a Shizuku UserService (`DaemonService`, shell rights). The in-app guide walks through these steps.

1. Install Shizuku, start it (wireless debugging), then open User Switch and grant the permission.
2. **After a reboot**: without root, Shizuku does not start on its own. A notification reminds you (with an "Open Shizuku" button). Once Shizuku runs, the daemon comes back **on its own, usually within 1 to 2 minutes** (sometimes longer: Android spaces out alarms for rarely opened apps), without opening the app; tapping the notification restarts it right away.

   Why the delay: Shizuku sends its binder only to its own app at startup, and to other granted apps only when their process changes state. `ShizukuWatch` (a non-waking alarm, every 60 s, armed at boot and when Shizuku stops) causes that change. The system also causes it by itself about 90 s later. The alarm is armed only if the daemon has already run once through Shizuku, and not if you stopped it yourself. It disarms as soon as the daemon runs, and gives up after 6 h or after 3 launches without a connection (a crashing daemon).

The daemon started by Shizuku survives profile switches. Only one daemon runs at a time: the one that starts stops the other (file `/data/local/tmp/userswitch.pid`).

**If the framework restarts** (a `system_server` crash, without a phone reboot): the daemon survives and reconnects to the new `system_server` (`activityManager()` asks for the `activity` service again when the binder died). Seen on the Android 17 beta: no heartbeat lost, the app woken within 30 s. Shizuku stays bound to the old `system_server`: restart it in its app, and the guide then points at step 5.

## Build and deploy

Requirements: JDK 17 or later, build-tools 36, `platforms/android-36` in `~/Android/Sdk`. The Shizuku 13.1.5 client libraries (Maven Central `dev.rikka.shizuku:{api,provider,shared,aidl}`) are in `libs/`.

```sh
./build.sh              # → build/userswitch.apk
adb tcpip 5555          # once per phone boot: unlike wireless debugging, this mode survives profile switches
scripts/deploy.sh       # installs the app for profile 0 and (re)starts the daemon
scripts/deploy.sh --start   # only restarts the daemon (after a phone reboot)
```

`scripts/phone.sh` picks a phone plugged in over USB first; otherwise it finds the phone on the network, whose IP changes on every Wi-Fi reconnect.
Daemon log: `adb shell cat /data/local/tmp/userswitch.log`.

## Tested compatibility

| Device | System | Checked |
|---|---|---|
| Pixel 10 Pro XL | GrapheneOS, Android 17 | buttons, recording, profile switch (also with the screen off), Shizuku, interface, hardening card |
| Pixel 11 Pro Fold | stock Android 17 beta | buttons, profile round trip (1 ms per switch), Shizuku, interface folded and unfolded, recovery after a framework crash |
| `small_phone` emulator | Android 11 | interface, baseline palette, 3-button navigation scrim |
| `pixel_tablet` emulator | Android 12 | two-pane interface, wallpaper colors |
| `pixel_fold` emulator | Android 14 | system Material 3 roles, fold and unfold without recreating the activity |

On emulators only the interface is checked: no hardware buttons, no daemon.

## Current limits

- After a reboot, Shizuku has to be started by hand (no Shizuku autostart without root); the daemon follows within 1 to 2 minutes.
- Presses are not intercepted: the volume still changes during a sequence.
- The repeated sequence must be identical to the recorded one.
- **Stock Android 17 beta** (Pixel 11 Pro Fold, DP11): when switching back to the main profile, SystemUI crashes every time (`ElementKey(screenrecord)`, then `controls`, "was composed 2 times" in quick settings). Sometimes `system_server` follows, as the keyguard does not show within 20 s, and the framework restarts. It is a beta bug: the switch is the same one Settings uses. Removing a tile only moves the crash.
- On that same beta, `development_settings_enabled` and `adb_wifi_enabled` read as 0 for the app even when they are on: the guide only trusts a value of 1.
- On the Pixel 11 Pro Fold, three devices declare the keys (`gpio_keys`, the fingerprint sensor in the power button, `uinput_nav`). Only `gpio_keys` sends presses today; if another one doubled a press, the sequence in progress would be dropped.
- Right after opening the app, the daemon status stays "waiting" until the next heartbeat (30 s at most).

---

## Français

Change de profil utilisateur avec une séquence de boutons physiques, sans root. Conçu pour GrapheneOS, fonctionne aussi sur un Pixel sous Android d'origine (voir [Compatibilité testée](#compatibilité-testée)).

### Fonctionnement

- **Programme de fond** (`src/dev/userswitch/daemon/Main.java`) : il est lancé avec les droits shell d'ADB via `app_process` et survit à la coupure d'ADB. Il lit les boutons dans `/dev/input` (power, volume haut, volume bas), découpe les appuis en séquences (`UP UP DOWN:long`, accords `UP+DOWN`), envoie chaque séquence de 3 points ou plus à l'app par binder, puis change de profil si l'app renvoie une cible.
- **App** (`MainActivity`, `ConfigProvider`, `Store`) : elle enregistre les séquences et les associe à un profil ou à « Profil suivant ». Le découpage étant fait par le programme de fond, une séquence enregistrée est toujours découpée comme celle qu'on refait ensuite.

Langues : anglais (par défaut) et français (`res/values-fr/`). L'app suit la langue du système, et se règle à part dans Paramètres › Applis › User Switch › Langue (Android 13+, via `res/xml/locales_config.xml`). Pour ajouter une langue : un `res/values-<code>/strings.xml` et une ligne dans `locales_config.xml`.

Score d'une séquence : 1 point par appui, +1 pour un appui long (≥ 500 ms), +1 pour plusieurs boutons pressés ensemble. Une séquence se termine après 800 ms sans appui.

Seuls les vrais profils sont proposés comme cibles : un profil de travail ou l'Espace privé d'Android d'origine (`FLAG_PROFILE`) ne peut pas être ouvert par `switchUser` et n'apparaît pas.

### Interface

Material 3 sans bibliothèque : le projet se compile sans Gradle, donc pas de Material Components. `M3.java` dessine les formes, les états pressés, l'échelle typographique et les composants (cartes, boutons, puces) avec les vues du système.

- **Couleurs Material You**, sur trois niveaux, en clair et en sombre :
  - Android 14+ (`values-v34`) : les rôles Material 3 du système (`system_surface_container_*`, `system_primary_*`…) ;
  - Android 12–13 (`values-v31`) : les palettes du fond d'écran (`system_accent*`, `system_neutral*`), tons choisis à la main ;
  - Android 11 (`values`) : la palette de base Material 3 (violet), faute de couleurs dynamiques.
- **Icône** adaptative aux couleurs du fond d'écran (Android 12+), avec une couche monochrome pour les icônes à thème.
- **Écran** : carte d'état (programme de fond, Shizuku), bannière d'enregistrement avec compte à rebours, séquences en liste groupée avec les touches en puces, encart « Sans Shizuku » repliable avec bouton Copier, bouton flottant « Enregistrer ». Le contenu passe sous les barres système, avec un fond derrière la barre d'état et un voile derrière une navigation à 3 boutons.
- **Guide Shizuku** en 6 étapes, avec un bouton par étape (Play Store, À propos du téléphone, options développeur, Shizuku, autorisation). Il suit l'avancement et se replie dès que le programme de fond tourne. Une fois Shizuku associé, un Shizuku arrêté renvoie directement à l'étape « Démarre Shizuku ».
- **Pliables et tablettes** : une colonne en dessous de 600 dp de large, deux panneaux au-dessus (état et guide à gauche, séquences à droite). Plier ou déplier réorganise l'écran sans recréer l'activité (`configChanges`). Le conseil pour l'association Shizuku s'adapte : Fold ouvert, écran partagé ; Fold fermé, code saisi dans la notification de Shizuku.

### Durcissement

`Hardening.java`. Partout, l'app :

- demande le marquage mémoire (MTE) en mode synchrone (`android:memtagMode="sync"`), appliqué là où le matériel et le système l'activent ;
- masque les fenêtres en surimpression au-dessus d'elle (`setHideOverlayWindows`) ;
- ignore les touchers qui passent à travers une autre fenêtre (`setFilterTouchesWhenObscured`), puisqu'elle accorde l'autorisation Shizuku.

Sur GrapheneOS, une carte « Durcissement GrapheneOS » liste les réglages par app que seul l'utilisateur peut activer (marquage mémoire, mode de compatibilité désactivé, chargement dynamique de code restreint, autorisation Capteurs refusée) et ouvre la page de l'app dans les Paramètres. GrapheneOS est reconnu à ses applis système (`app.grapheneos.apps`, `app.grapheneos.info`) : ses builds reproductibles portent un utilisateur et un hôte génériques, inutilisables pour le détecter.

### Avec Shizuku (sans PC)

L'app lance le programme de fond comme UserService Shizuku (`DaemonService`, droits shell). Le guide intégré à l'app reprend ces étapes.

1. Installe Shizuku, démarre-le (débogage sans fil), puis ouvre User Switch et accorde l'autorisation.
2. **Après un redémarrage** : sans root, Shizuku ne démarre pas tout seul. Une notification le rappelle (bouton « Ouvrir Shizuku »). Une fois Shizuku démarré, le programme de fond repart **tout seul, en général en 1 à 2 minutes** (parfois plus : Android espace les alarmes des apps rarement ouvertes), sans ouvrir l'app ; toucher la notification le relance tout de suite.

   Pourquoi ce délai : Shizuku n'envoie son binder qu'à sa propre app au démarrage, et aux autres apps autorisées seulement quand leur processus change d'état. `ShizukuWatch` (une alarme non réveillante, toutes les 60 s, armée au démarrage et quand Shizuku s'arrête) provoque ce changement d'état. Le système le provoque aussi de lui-même environ 90 s après. L'alarme n'est armée que si le programme de fond a déjà tourné une fois via Shizuku, et pas si tu l'as arrêté toi-même. Elle se désarme dès qu'il tourne, et abandonne au bout de 6 h ou après 3 lancements sans connexion (programme de fond qui plante).

Le programme de fond lancé par Shizuku survit au changement de profil. Un seul programme de fond tourne à la fois : celui qui démarre arrête l'autre (fichier `/data/local/tmp/userswitch.pid`).

**Si le framework redémarre** (plantage de `system_server`, sans redémarrage du téléphone) : le programme de fond survit, et reprend sa connexion au nouveau `system_server` (`activityManager()` redemande le service `activity` quand le binder est mort). Constaté sur Android 17 bêta : aucun signal de vie perdu, l'app réveillée dans les 30 s. Shizuku, lui, reste lié à l'ancien `system_server` : il faut le redémarrer dans son app, et le guide pointe alors l'étape 5.

### Compiler et déployer

Prérequis : JDK 17 ou plus, build-tools 36, `platforms/android-36` dans `~/Android/Sdk`. Les bibliothèques client Shizuku 13.1.5 (Maven Central `dev.rikka.shizuku:{api,provider,shared,aidl}`) sont dans `libs/`.

```sh
./build.sh              # → build/userswitch.apk
adb tcpip 5555          # une fois par démarrage du téléphone : contrairement au débogage sans fil, ce mode survit au changement de profil
scripts/deploy.sh       # installe l'app pour le profil 0 et (re)lance le programme de fond
scripts/deploy.sh --start   # relance seulement le programme de fond (après un redémarrage du téléphone)
```

`scripts/phone.sh` choisit d'abord un téléphone branché en USB ; sinon il retrouve le téléphone sur le réseau, dont l'IP change à chaque reconnexion Wi-Fi.
Journal du programme de fond : `adb shell cat /data/local/tmp/userswitch.log`.

### Compatibilité testée

| Appareil | Système | Vérifié |
|---|---|---|
| Pixel 10 Pro XL | GrapheneOS, Android 17 | boutons, enregistrement, changement de profil (aussi écran éteint), Shizuku, interface, carte de durcissement |
| Pixel 11 Pro Fold | Android 17 bêta d'origine | boutons, aller-retour de profil (1 ms par changement), Shizuku, interface pliée et dépliée, reprise après plantage du framework |
| Émulateur `small_phone` | Android 11 | interface, palette de base, voile de navigation à 3 boutons |
| Émulateur `pixel_tablet` | Android 12 | interface en deux panneaux, couleurs du fond d'écran |
| Émulateur `pixel_fold` | Android 14 | rôles Material 3 du système, pliage et dépliage sans recréer l'activité |

Sur émulateur, seule l'interface est vérifiée : ni boutons physiques, ni programme de fond.

### Limites actuelles

- Après un redémarrage, il faut démarrer Shizuku à la main (pas de démarrage automatique de Shizuku sans root) ; le programme de fond suit en 1 à 2 minutes.
- Les appuis ne sont pas interceptés : le volume change quand même pendant la séquence.
- La séquence refaite doit être identique à celle enregistrée.
- **Android 17 bêta d'origine** (Pixel 11 Pro Fold, DP11) : au retour vers le profil principal, System UI plante à chaque fois (`ElementKey(screenrecord)`, puis `controls`, « was composed 2 times » dans les réglages rapides). Parfois `system_server` suit, faute d'écran de verrouillage en 20 s, et le framework redémarre. C'est un bug de la bêta : le changement de profil est le même que celui des Paramètres. Retirer une tuile ne fait que déplacer le plantage.
- Sur cette même bêta, `development_settings_enabled` et `adb_wifi_enabled` valent 0 pour l'app même quand ils sont activés : le guide ne se fie qu'à une valeur 1.
- Sur le Pixel 11 Pro Fold, trois périphériques déclarent les touches (`gpio_keys`, le capteur d'empreinte du bouton power, `uinput_nav`). Seul `gpio_keys` envoie des appuis aujourd'hui ; si un autre doublait un appui, la séquence en cours serait abandonnée.
- Juste après l'ouverture de l'app, l'état du programme de fond reste « en attente » jusqu'au signal de vie suivant (30 s au plus).
